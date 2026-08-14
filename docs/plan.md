# Build plan

What shipped, in what order — as opposed to [specification.md](specification.md) (what was asked for) or [architecture.md](architecture.md) (how it works and why). All steps below are complete.

## Backend

1. **Flyway schema + JPA entities + repositories.** `src/main/resources/db/migration/V1__init_schema.sql` (`submission`, `submission_file`, `outbox`); entities and enums in `src/main/java/com/fileintake/submission/` and `src/main/java/com/fileintake/outbox/OutboxEvent.java`; repositories including the pessimistic-lock commit query and the `SKIP LOCKED` outbox batch query.
2. **`UploadTransport` interface + `FakeUploadTransport`.** `src/main/java/com/fileintake/upload/UploadTransport.java`, `FakeUploadTransport.java` — in-memory fake with `simulateUploadSuccess`/`simulateUploadFailure`/`reset()` controls, active by default.
3. **Endpoints wired to the fake, with integration tests.** `SubmissionController`, `SubmissionService` (create, issueUploadCredentials), `SubmissionFileService` (completeFile), all in `src/main/java/com/fileintake/submission/`. Tests: `SubmissionCreationIntegrationTest`, `UploadCredentialsIntegrationTest`, `CompleteFileIntegrationTest`, `GetSubmissionIntegrationTest`.
4. **Commit transaction + outbox insert, with concurrency tests.** `SubmissionService.commit`. Tests: `SubmissionCommitIntegrationTest`, including the concurrent-double-commit test (two threads hit `/commit` simultaneously, assert exactly one outbox row and identical `committedAt`). This was the checkpoint where the fake-backed system became fully testable end to end.
5. **`XmlMultipartTransport` real GCS implementation.** `src/main/java/com/fileintake/upload/xml/` — signs PUT URLs via `Storage.signUrl`'s V4 signing under Workload Identity (IAM SignBlob), not static HMAC keys; see [architecture.md](architecture.md) and [specification.md](specification.md#known-gaps-against-this-spec) for why. Selected via `fileintake.upload.transport=xml-multipart`; zero changes to service/controller code. Tests: `XmlMultipartTransportTest` (Mockito, no live GCS needed).
6. **Outbox poller + Pub/Sub publisher.** `src/main/java/com/fileintake/outbox/OutboxPoller.java` (runs only under the `worker` Spring profile), `OutboxPublisher` port with `FakeOutboxPublisher`/`PubSubOutboxPublisher`. Tests: `OutboxPollerIntegrationTest`.
7. **Reaper job.** `src/main/java/com/fileintake/reaper/` — `ReaperJob`, `StagingObjectStore` port with fake/GCS adapters, `ReaperRunner` (CronJob entry point, `@Profile("reaper")`). Tests: `ReaperJobIntegrationTest`.

38 backend tests total, all against real Postgres via Testcontainers (except the Mockito-based `XmlMultipartTransportTest`).

## Frontend

Added in a follow-up request, after the backend was complete — see [specification.md](specification.md#frontend-addition-later-request) for why GitHub Pages forced a mock/real dual-mode design.

1. **Scaffold.** Vite + React + TypeScript in `frontend/`; `types/api.ts` mirrors the 9 backend DTOs field-for-field; `api/client.ts` (the `FileIntakeApiClient` interface), `api/realClient.ts`, `api/upload.ts`.
2. **Mock backend.** `mock/store.ts`, `mock/mockClient.ts`, `mock/scenarios.ts` (deterministic failure for any file named with "fail" in it, plus a random-failure toggle). Tests: `tests/mockClient.test.ts` — 400/404/409/422, idempotent commit.
3. **State.** `state/settings.ts` + `state/SettingsContext.tsx` (mode + API base URL, `localStorage`-backed), `api/backend.ts` (resolves mock vs. real), `state/useSubmissionFlow.ts` (the create → per-file upload pipeline → commit orchestration, `useReducer`-based).
4. **Components.** `components/` — `CreateSubmissionForm`, `FileDropzone`, `FileSlotList`/`FileSlotCard`, `StatusBadge`, `SubmissionSummary`, `SettingsPanel`, `LoadSubmissionForm`; wired together in `App.tsx`.
5. **Component tests.** `tests/happyPath.test.tsx` (10 files, all upload, commit) and `tests/retryFlow.test.tsx` (one file fails deterministically, gets retried, then commits) — both render the real `<App />` against the mock backend.
6. **Backend CORS.** `src/main/java/com/fileintake/common/config/CorsConfig.java`, off by default; see [architecture.md](architecture.md#cors-is-two-separate-surfaces).
7. **GitHub Pages deployment.** `.github/workflows/deploy-frontend.yml`, `vite.config.ts`'s `base: '/file-intake-service/'`, `frontend/README.md` (GCS bucket CORS step, real-mode manual smoke-test checklist).

12 frontend tests total (Vitest + React Testing Library), all against the mock backend.

## Post-deployment fix

The first `workflow_dispatch` run of `deploy-frontend.yml` failed at the `npm test` step: `actions/setup-node@v4` was pinned to Node 20, but several installed dependencies (`jsdom@30.x`, `undici@8.x`, `@testing-library/jest-dom@7.x`) require Node ≥22, and Vitest's forked test workers crashed with `TypeError: webidl.util.markAsUncloneable is not a function` before any test ran. Fixed by bumping `node-version` to `22` in the workflow — no application or test code changes were needed, since the same suite already ran clean locally on Node 22.
