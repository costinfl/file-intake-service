# file-intake-service frontend

A React + TypeScript + Vite frontend for the [file-intake-service](../README.md) backend, deployable as a static site to GitHub Pages.

## Modes

The app has two modes, switched from the settings panel (gear icon, top right) or via `?mode=real&apiBaseUrl=...` query params on first load:

- **Mock mode (default)** — an in-memory, in-browser implementation of the same 5-endpoint API contract, with realistic status transitions and error cases. Works with nothing deployed; this is what runs on GitHub Pages out of the box. Name a file with "fail" in it (e.g. `fail-me.pdf`) to trigger the deterministic failure/retry path, or enable "Simulate random upload failures" in settings for organic failures.
- **Real mode** — calls an actual running backend at a configured API base URL, and PUTs file bytes to the real signed GCS URLs it returns.

## Local development

```
npm install
npm run dev
```

## Testing

```
npm test
```

Covers the mock backend's contract (400/404/409/422, idempotent commit) and two end-to-end component flows against the assembled app: the happy path (10 files, all upload, commit) and the retry path (one file fails, gets retried, then commits).

## Using real mode

Real mode requires two things beyond what this frontend controls, since GitHub Pages only hosts static files:

1. **A running backend reachable over HTTPS from the browser**, with CORS enabled for this frontend's origin. Set on the backend:

   ```
   FILEINTAKE_CORS_ALLOWED_ORIGINS=https://<owner>.github.io
   ```

   (see `CorsConfig.java` in the backend — CORS is off entirely unless this is set).

2. **CORS configured on the GCS bucket itself**, separately from the backend. The browser PUTs file bytes directly to a signed GCS URL, bypassing the backend entirely, so the bucket needs its own CORS policy allowing `PUT` and the `x-goog-content-length-range` / `x-goog-if-generation-match` request headers from this frontend's origin. This is infrastructure, not application code:

   ```
   gcloud storage buckets update gs://<bucket> --cors-file=cors.json
   ```

   where `cors.json` allows origin `https://<owner>.github.io`, method `PUT`, and those two headers.

Without step 2, uploads fail with a browser CORS error even when the backend's CORS (step 1) is configured correctly.

### Manual smoke-test checklist (real mode against a local backend)

No live backend is deployed for this repo's CI, so real mode isn't exercised automatically. To check it by hand:

1. Run the backend locally with the fake upload transport (no GCS bucket needed) and CORS open for the Vite dev server:
   ```
   FILEINTAKE_CORS_ALLOWED_ORIGINS=http://localhost:5173 mvn spring-boot:run
   ```
   (run from the repo root, not `frontend/`)
   (`fileintake.upload.transport` already defaults to `fake` — see the backend's `application.yml`.)
2. `npm run dev` here, open the app, open Settings, switch to Real mode, set the API base URL to `http://localhost:8080`, Save.
3. Walk the flow: create a submission with 10 files, watch them all reach Uploaded, Commit. Since the backend is running with the fake transport, uploads succeed without actually touching GCS.

## Deployment

`.github/workflows/deploy-frontend.yml` builds and deploys this directory to GitHub Pages on push to `main` under `frontend/**`, using the official `actions/deploy-pages` action.

**One-time manual step required**: in the repo's Settings → Pages, set "Build and deployment → Source" to **GitHub Actions**. No workflow file can do this for you.

`vite.config.ts` sets `base: '/file-intake-service/'` since this is a project (not user/org) Pages site, served under that path rather than the root.
