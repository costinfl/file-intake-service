# file-intake-service

A Java 21 / Spring Boot 3 service for atomic multi-file submissions: a client submits exactly 10 files (~50MB each) as one unit, uploads bytes directly to Google Cloud Storage via short-lived signed URLs (never through the application), and the service tracks state in Postgres and publishes a `submission.committed` event via an outbox + Pub/Sub once all 10 files are verified and the submission is committed.

## Backend

- Java 21, Spring Boot 3, Postgres (Flyway-migrated), GCS, Pub/Sub.
- Build: `mvn test` (uses Testcontainers — requires Docker).
- Run locally: `mvn spring-boot:run` (defaults to an in-memory fake upload transport, no GCS bucket needed).
- Endpoints: `POST /submissions`, `POST /submissions/{id}/files/{fileId}/upload-credentials`, `POST /submissions/{id}/files/{fileId}/complete`, `GET /submissions/{id}`, `POST /submissions/{id}/commit`.

### Running against real GCS/Pub-Sub code paths locally (no GKE/GCP account needed)

No GKE cluster to deploy to yet? `docker-compose.yml` runs [fake-gcs-server](https://github.com/fsouza/fake-gcs-server) and the official Pub/Sub emulator alongside Postgres, so the *real* `XmlMultipartTransport`/`PubSubOutboxPublisher` code (signed URLs, header enforcement, outbox publishing) can be exercised end to end instead of just the in-memory fakes:

```
docker compose up -d
GCS_HOST=http://localhost:4443 GCS_BUCKET=test-bucket GCP_PROJECT_ID=local-project \
PUBSUB_EMULATOR_HOST=localhost:8085 \
mvn spring-boot:run -Dspring-boot.run.arguments="--fileintake.upload.transport=xml-multipart --fileintake.outbox.publisher=pubsub --spring.profiles.active=worker"
```

See [`docs/architecture.md`](docs/architecture.md#local-gcp-emulation-no-gkegcp-account-needed) for how the emulator wiring works. Leave `fileintake.upload.transport`/`fileintake.outbox.publisher` at their defaults (`fake`) for ordinary local dev — the emulator stack is only needed to verify the real GCS/Pub-Sub code itself.

## Frontend

A React + TypeScript demo UI for the flow above lives in [`frontend/`](frontend/README.md), deployable to GitHub Pages. It defaults to a self-contained mock mode (no backend needed) and can be pointed at a real deployed backend — see the frontend README for details, including the CORS setup real mode requires.

## Docs

- [`docs/specification.md`](docs/specification.md) — the original brief this was built from.
- [`docs/architecture.md`](docs/architecture.md) — how it's built and why.
- [`docs/plan.md`](docs/plan.md) — what shipped, in what order.
- [`docs/diagrams.md`](docs/diagrams.md) — architecture diagram plus the success/failure/edge-case flows (retry after a failed upload, commit blocked by unfinished files, concurrent double commit, reaper expiry).
