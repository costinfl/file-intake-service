# Specification

This is the original brief the backend was built from, kept here for reference.

## Context

Java 21 / Spring Boot 3 service on GKE. Users submit **exactly 10 files, ~50MB each, as one atomic submission**. Up to 200 concurrent submitters (~100GB burst).

**Invariant: bytes never transit the app.** Pods issue upload credentials and manage state only. Postgres is source of truth; GCS is dumb bytes.

Stack: Spring Boot 3 (web, validation, data-jpa), Postgres (Cloud SQL), Flyway, google-cloud-storage, google-cloud-pubsub, Testcontainers, JUnit 5.

## Upload transport

Default: **GCS XML API multipart** (S3-compatible, HMAC keys) so the browser can use Uppy's AWS-S3-multipart plugin unchanged.
Put it behind `interface UploadTransport { InitUpload init(...); UploadedObject verify(...); }` with `XmlMultipartTransport` as the impl, so native resumable sessions can be swapped in without touching domain code.

## Schema (Flyway V1)

```
submission(id uuid pk, owner_id, status, created_at, expires_at, committed_at)
  status: PENDING | COMMITTED | EXPIRED | FAILED
submission_file(id uuid pk, submission_id fk, ordinal int, object_path,
  declared_name, declared_size, status, gcs_generation bigint, crc32c, size_bytes,
  updated_at)
  status: PENDING | UPLOADING | UPLOADED | FAILED
  unique(submission_id, ordinal)
outbox(id, aggregate_id, type, payload jsonb, created_at, published_at null)
  index on (published_at) where published_at is null
```

Object path: `staging/{submissionId}/{fileId}`. Never move objects on commit.

## Endpoints

- `POST /submissions` → creates submission + 10 `submission_file` rows, returns ids + `expires_at`.
- `POST /submissions/{id}/files/{fileId}/upload-credentials` → transport credentials scoped to that one object path, with a content-length-range cap. Never log the credential/session URI.
- `POST /submissions/{id}/files/{fileId}/complete` → server calls GCS `stat`, records `generation`, `size`, `crc32c`, sets `UPLOADED`. Reject size mismatch.
- `POST /submissions/{id}/commit` → **idempotent**. One tx: `SELECT ... FOR UPDATE` on submission → verify all 10 rows `UPLOADED` and GCS-reported size/crc32c match → `status=COMMITTED` → insert `outbox` row. Already-committed returns 200 with same body.
- `GET /submissions/{id}` → per-file status so the client re-uploads only failures.

Partial failure is the normal path: never reset the other 9 files.

## Async

Outbox poller (`@Scheduled`, `FOR UPDATE SKIP LOCKED`, batch 100) publishes `submission.committed` to Pub/Sub, stamps `published_at`. **No GCS object-finalize notifications** — per-file events would start work on submissions that never complete.

Workers live in a separate Deployment. Consumers idempotent (at-least-once). Dead-letter topic configured from day one.

## Reaper

`CronJob` hourly: `PENDING` submissions past `expires_at` (24h) → delete staged objects → `EXPIRED`. Bucket lifecycle rule on `staging/` at 7d is a backstop only, not the mechanism.

## Guardrails

- No `MultipartFile`, no proxying bytes, ever.
- `ifGenerationMatch=0` on upload so a retry can't overwrite a different file.
- Validate type from magic bytes server-side after upload, never `Content-Type`.
- Readers resolve object paths through the DB; uncommitted objects are invisible.
- Batch part-completion writes — this is a write-heavy pattern that will thrash the connection pool.
- Workload Identity for auth, no SA JSON keys. If signing URLs, the SA needs `roles/iam.serviceAccountTokenCreator` **on itself**.

## Build order

1. Flyway schema + JPA entities + repos.
2. `UploadTransport` interface + fake impl.
3. Endpoints 1–3 with the fake; integration tests on Testcontainers Postgres.
4. Commit tx + outbox insert. Test: concurrent double-commit, missing file, size mismatch, replay.
5. `XmlMultipartTransport` real impl + config.
6. Outbox poller + Pub/Sub publisher.
7. Reaper job.

Stop after each step and report. Write tests as you go — do not batch them at the end.

## Frontend addition (later request)

A follow-up request asked for a frontend "so we can see it with GitHub Pages." Since GitHub Pages only serves static files and cannot run the Spring Boot backend, the chosen approach was: a real, functional React frontend that calls the actual API, defaulting to a built-in mock/demo mode so it works standalone on GitHub Pages with nothing deployed, with an easy switch to point at a real backend URL later. See [architecture.md](architecture.md) for how this was built.

## Known gaps against this spec

- **Magic-byte content-type validation** (guardrails list) was not implemented — it isn't part of any of the 7 build-order steps' concrete deliverables, and doing it would mean reading bytes back from GCS after upload (a small departure from "bytes never transit the app" that the spec doesn't resolve). Flagged as a gap, not silently dropped.
- **HMAC-key S3-compatible signing** for Uppy's `aws-s3-multipart` plugin, as literally described in "Upload transport" above, was not implemented. It conflicts with the Guardrails section's Workload-Identity/no-SA-JSON-key/self-impersonation requirement, which points at Google's own IAM-based V4 signing instead. Asked the user directly; they chose IAM-based V4 signing (`Storage.signUrl`), which produces one presigned PUT URL per file and pairs with Uppy's plain `aws-s3` plugin rather than `aws-s3-multipart`. See `docs/architecture.md`.
