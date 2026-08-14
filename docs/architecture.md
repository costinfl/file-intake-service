# Architecture

How the system is built, and why — as opposed to [specification.md](specification.md) (what was asked for) or [plan.md](plan.md) (what shipped, in what order).

## Backend

### Core invariant: bytes never transit the app

Pods never read or write file bytes. They issue short-lived, scoped upload credentials against GCS and track state in Postgres, which is the sole source of truth. `UploadTransport` (`src/main/java/com/fileintake/upload/UploadTransport.java`) is the seam: `init()` issues credentials for one object path, `verify()` reads back what actually landed in storage (size, crc32c, generation) without ever touching the bytes itself.

### App-assigned UUIDs, not DB-generated

`Submission` and `SubmissionFile` (`src/main/java/com/fileintake/submission/`) assign their own `UUID.randomUUID()` ids in the service layer instead of letting Hibernate/Postgres generate them. `POST /submissions` has to build the parent row and 10 children in one shot, and each file's `object_path` (`staging/{submissionId}/{fileId}`) embeds its own id — a DB-generated id would need an extra round trip before the path could even be computed.

This has a consequence: Hibernate's default new-vs-detached check (`id == null`) always says "detached" for an app-assigned id, which would route `save()` through `merge()` instead of `persist()` and fail with `EntityNotFoundException` on the very first insert. Both entities implement `Persistable<UUID>` with a transient `isNew` flag (true from the constructor, flipped to false by a `@PostLoad`/`@PostPersist` callback) to fix this.

### Enums as `varchar` + `CHECK`, not native Postgres `ENUM`

`SubmissionStatus` and `SubmissionFileStatus` map via plain `@Enumerated(EnumType.STRING)` onto `varchar` columns with a `CHECK` constraint (see `V1__init_schema.sql`). Native Postgres enum types complicate Flyway migrations — `ALTER TYPE ... ADD VALUE` has DDL-transaction restrictions that fight Flyway's transaction-wrapped migrations — for no real benefit at this scale.

### No optimistic locking

No `@Version` field anywhere. Concurrency is handled explicitly instead: a pessimistic `SELECT ... FOR UPDATE` (`SubmissionRepository.findByIdForUpdate`) serializes commit against itself and against the reaper's `markExpired`; every other write is a single-row atomic update that never races another writer of the same row.

### Split-transaction pattern around network calls

Any service method that calls out to `UploadTransport` (or, later, `OutboxPublisher`) never holds a database transaction across that call. `SubmissionService.issueUploadCredentials` and `SubmissionFileService.completeFile` each split into: a short read-only transaction to validate, the network call with no transaction open, then a short write transaction to persist the result. This is done with `TransactionTemplate`, not `@Transactional` on private helper methods — a `@Transactional`-annotated method called via `this.` from within the same class bypasses Spring's proxy entirely and silently runs with no transaction at all, which is why `TransactionTemplate` is used instead of trying to split methods and rely on self-invocation.

The direct motivation is the "batch part-completion writes will thrash the connection pool" guardrail: under a 200-submitter × 10-file burst, holding a DB connection open for the duration of a network call to GCS would exhaust the Hikari pool fast.

The one deliberate exception is the outbox poller (`OutboxPoller.pollAndPublish`), which *does* hold its transaction (and therefore its `FOR UPDATE SKIP LOCKED` row lock) across the publish call to Pub/Sub — there, the lock itself is what stops two poller replicas from publishing the same event concurrently, so it can't be dropped before the network call the way the upload endpoints' locks can.

### Commit: idempotent, single transaction, no I/O

`SubmissionService.commit` is one `@Transactional` method:

1. `SELECT ... FOR UPDATE` locks the submission row.
2. Already `COMMITTED` → return the existing response immediately. This is both the idempotency mechanism and what makes concurrent double-commit safe: a second caller blocks on the row lock, then observes `COMMITTED` here and no-ops.
3. `EXPIRED`/`FAILED` → 409, nothing written.
4. Otherwise, re-verify all 10 files are `UPLOADED` with matching size/crc32c (defense in depth — `/complete` already checked this, but commit re-checks rather than trusting it blindly) — any failure is 409 with **no file rows touched**, since partial failure has to be recoverable by re-uploading only the failed files.
5. Otherwise, flip to `COMMITTED`, insert one `outbox` row with a JSON payload of the submission and its 10 files, and return.

Commit never calls `UploadTransport` — it trusts what `/complete` already recorded, consistent with "readers resolve object paths through the DB."

### Outbox pattern, and why an event never goes straight to Pub/Sub

Committing a submission and publishing `submission.committed` can't be a single atomic operation across a Postgres transaction and a Pub/Sub publish call. The outbox table makes the write atomic (the `outbox` insert happens in the *same* transaction as the `COMMITTED` status flip) and defers the actual publish to `OutboxPoller`, a separate `@Scheduled` job that only runs under the `worker` Spring profile — API pods serving submission traffic never run the poller. `OutboxRepository.lockBatchForPublish` uses native SQL for `SELECT ... FOR UPDATE SKIP LOCKED ORDER BY id LIMIT :batchSize`, since there's no portable JPQL equivalent.

### Ports and fakes, used consistently

The same shape recurs three times, once per external system the service talks to:

| Port | Real adapter | Fake/default adapter | Selected by |
|---|---|---|---|
| `UploadTransport` | `upload/xml/XmlMultipartTransport` | `upload/FakeUploadTransport` | `fileintake.upload.transport` |
| `OutboxPublisher` | `outbox/PubSubOutboxPublisher` | `outbox/FakeOutboxPublisher` | `fileintake.outbox.publisher` |
| `StagingObjectStore` | `reaper/GcsStagingObjectStore` | `reaper/FakeStagingObjectStore` | `fileintake.upload.transport` (reused — same underlying question) |

Each fake is a `@Component` with `matchIfMissing = true` so it's the default; each real adapter is a sibling `@Component` gated by the same property with a different value. This is what let Steps 1–4 build and test the entire domain (schema, endpoints, commit transaction, concurrency behavior) against a fully working fake before any GCS/Pub-Sub code existed, and it's why local dev and CI never need real GCP credentials unless real mode is explicitly selected.

`StagingObjectStore` (delete-only) is deliberately a separate port from `UploadTransport` rather than adding a `delete()` method to it — deleting abandoned staging objects is the reaper's concern, unrelated to the upload-signing contract that `UploadTransport` and its fake are built around, and adding it would mean touching `FakeUploadTransport`'s test-facing surface for an operation none of the upload/commit tests need.

### Reaper

`ReaperJob.reap()` finds `PENDING` submissions past their 24h `expires_at`, deletes each of their staged objects (network I/O, outside any transaction), then flips the submission to `EXPIRED` in a short write transaction that re-locks via `findByIdForUpdate` and re-checks status before writing — this is what stops a submission a client legitimately committed in the window between the reaper's read and its write from being clobbered back to `EXPIRED`. A failed object delete is logged and left for the bucket's 7-day lifecycle rule to catch as backstop; it doesn't block marking the submission `EXPIRED`, since an orphaned staging object is already invisible to readers (they resolve paths through the DB) either way. `ReaperRunner` is a `@Profile("reaper")`-gated `CommandLineRunner` that calls `reap()` once and calls `System.exit` on `SpringApplication.exit`'s code — a k8s CronJob wants the process to exit after one run, not stay resident.

## Frontend

### Why not Uppy

Uppy's Dashboard/Core model is built for an open-ended, unordered file queue. This app's contract is the opposite: `POST /submissions` requires the client to declare all 10 files' names and sizes *upfront*, and the server creates 10 ordinal-keyed placeholder rows before any byte is picked for upload against them. Bending Uppy to "slot 7 is currently empty, waiting for a file to be (re)attached to it" would fight its state model for no real benefit — the actual upload mechanics Uppy would provide (progress events, an S3-style `getUploadParameters` callback) are about 20 lines of hand-rolled `XMLHttpRequest`, kept in `frontend/src/api/upload.ts`.

### One interface, two backends

`FileIntakeApiClient` (`frontend/src/api/client.ts`) is implemented by both `RealApiClient` (`realClient.ts`, a thin `fetch` wrapper) and `MockApiClient` (`mock/mockClient.ts`, an in-memory reimplementation of the same 5-endpoint contract, replicating the real backend's exact status transitions and error rules — 400/404/409/422, idempotent commit). `getBackend()` (`api/backend.ts`) is the only place that decides which one is active; every UI component calls `getBackend().api.createSubmission(...)` etc. and never branches on mode itself.

Mock mode is the default with zero configuration — this is what makes the site work standalone on GitHub Pages with no backend deployed at all. Settings (`mode`, `apiBaseUrl`) live in `localStorage`, switchable from a settings panel or via `?mode=real&apiBaseUrl=...` query params on first load.

### Real-mode uploads use XHR, not fetch

`fetch` has no upload-progress event; `XMLHttpRequest.upload.onprogress` does, and the UI's whole point is per-file progress bars. Otherwise the two are equivalent here — the backend's signed URL is already fully authorized, so there's no client-side signing logic either way, just setting the two headers the backend returns (`x-goog-content-length-range`, `x-goog-if-generation-match`) and PUTting the `File` object as the body.

### CORS is two separate surfaces

Real mode from a GitHub Pages origin needs CORS in two unrelated places:

1. **The Spring backend's JSON endpoints** — `CorsConfig` (`src/main/java/com/fileintake/common/config/CorsConfig.java`), off by default via `@ConditionalOnProperty(name = "fileintake.cors.allowed-origins")`. That property is deliberately left *undeclared* in `application.yml` rather than defaulted to an empty string — an explicit empty-string default would still count as the property being present in Spring's `Environment`, defeating the conditional's absence check and loading the bean with zero allowed origins instead of skipping it entirely.
2. **The GCS bucket itself** — the browser PUTs file bytes directly to a signed GCS URL, bypassing the backend entirely, so the bucket needs its own CORS policy (`gcloud storage buckets update ... --cors-file=cors.json`). This is infrastructure, not application code; documented as a manual step in `frontend/README.md`.

Without (2), uploads fail with a browser CORS error even when (1) is configured correctly.
