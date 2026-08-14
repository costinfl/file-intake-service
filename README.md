# file-intake-service

A Java 21 / Spring Boot 3 service for atomic multi-file submissions: a client submits exactly 10 files (~50MB each) as one unit, uploads bytes directly to Google Cloud Storage via short-lived signed URLs (never through the application), and the service tracks state in Postgres and publishes a `submission.committed` event via an outbox + Pub/Sub once all 10 files are verified and the submission is committed.

## Backend

- Java 21, Spring Boot 3, Postgres (Flyway-migrated), GCS, Pub/Sub.
- Build: `mvn test` (uses Testcontainers — requires Docker).
- Run locally: `mvn spring-boot:run` (defaults to an in-memory fake upload transport, no GCS bucket needed).
- Endpoints: `POST /submissions`, `POST /submissions/{id}/files/{fileId}/upload-credentials`, `POST /submissions/{id}/files/{fileId}/complete`, `GET /submissions/{id}`, `POST /submissions/{id}/commit`.

## Frontend

A React + TypeScript demo UI for the flow above lives in [`frontend/`](frontend/README.md), deployable to GitHub Pages. It defaults to a self-contained mock mode (no backend needed) and can be pointed at a real deployed backend — see the frontend README for details, including the CORS setup real mode requires.
