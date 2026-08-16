# Diagrams

Visual companion to [architecture.md](architecture.md) (why) and [specification.md](specification.md) (what was asked for). GitHub renders the Mermaid blocks below inline.

- [System architecture](#system-architecture)
- [Status state machines](#status-state-machines)
- [Success path: full submission lifecycle](#success-path-full-submission-lifecycle)
- [Failure: upload verify fails, client retries](#failure-upload-verify-fails-client-retries)
- [Failure: commit attempted with files not all uploaded](#failure-commit-attempted-with-files-not-all-uploaded)
- [Edge case: concurrent double commit](#edge-case-concurrent-double-commit)
- [Failure: submission abandoned, reaper expires it](#failure-submission-abandoned-reaper-expires-it)
- [Local GCP emulation topology](#local-gcp-emulation-topology)

## System architecture

The core invariant — **file bytes never transit the application** — shapes everything else: API pods only ever exchange small JSON payloads and short-lived signed URLs; the browser talks to GCS directly for the bytes themselves.

```mermaid
flowchart LR
    Browser["Browser / frontend\n(mock or real mode)"]

    subgraph GKE["GKE"]
        API["API pods\n(SubmissionController)"]
        Worker["Worker pods\nOutboxPoller\n(profile: worker)"]
        Reaper["Reaper CronJob\n(profile: reaper)"]
    end

    PG[("Postgres\nsubmission / submission_file / outbox")]
    GCS[("GCS bucket\nstaging/{submissionId}/{fileId}")]
    PubSub{{"Pub/Sub topic\nsubmission-committed"}}

    Browser -- "1. create, get status,\ncommit (JSON)" --> API
    API -- "reads/writes state" --> PG
    Browser -- "2. issue upload-credentials (JSON)" --> API
    API -- "3. signUrl() via IAM SignBlob" --> GCS
    Browser -- "4. PUT file bytes directly\n(signed URL, never through API)" --> GCS
    Browser -- "5. complete (JSON)" --> API
    API -- "6. verify: read back\nsize/crc32c/generation" --> GCS

    Worker -- "SELECT ... FOR UPDATE SKIP LOCKED" --> PG
    Worker -- "publish submission.committed" --> PubSub

    Reaper -- "find PENDING past expires_at" --> PG
    Reaper -- "delete abandoned staged objects" --> GCS
```

## Status state machines

Two independent state machines: one per file, one per submission. Commit re-checks every file's status rather than trusting `/complete` blindly.

```mermaid
stateDiagram-v2
    [*] --> PENDING: created with submission
    PENDING --> UPLOADING: upload-credentials issued
    UPLOADING --> UPLOADED: complete() verifies\nsize + crc32c match
    UPLOADING --> FAILED: complete() finds\nnot-found or size mismatch
    FAILED --> UPLOADING: upload-credentials\nre-issued (retry)
    UPLOADED --> [*]
```

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /submissions
    PENDING --> COMMITTED: commit(), all 10 files\nUPLOADED and verified
    PENDING --> EXPIRED: reaper, past\n24h expires_at
    COMMITTED --> [*]
    EXPIRED --> [*]

    note right of PENDING
        commit() returns 409 and touches
        nothing if any file isn't UPLOADED
        or is EXPIRED/FAILED already
    end note
```

## Success path: full submission lifecycle

One file's upload/complete cycle shown; in practice all 10 run through this loop (concurrency-capped in the frontend) before commit is enabled.

```mermaid
sequenceDiagram
    actor U as Browser
    participant API as API pod
    participant DB as Postgres
    participant GCS
    participant Worker as Worker pod (OutboxPoller)
    participant PS as Pub/Sub

    U->>API: POST /submissions (10 declared files)
    API->>DB: insert submission + 10 submission_file rows (PENDING)
    API-->>U: 201 {submissionId, 10× fileId}

    loop for each of the 10 files
        U->>API: POST .../files/{fileId}/upload-credentials
        API->>DB: read+validate (short tx)
        API->>GCS: signUrl() [no tx open]
        API->>DB: file → UPLOADING (short tx)
        API-->>U: signed PUT URL + required headers

        U->>GCS: PUT file bytes (headers enforced:\nx-goog-content-length-range,\nx-goog-if-generation-match)
        GCS-->>U: 200

        U->>API: POST .../files/{fileId}/complete
        API->>DB: read (short tx)
        API->>GCS: verify(): get object metadata [no tx open]
        GCS-->>API: size, crc32c, generation
        API->>DB: file → UPLOADED + metadata (short tx)
        API-->>U: 200 {status: UPLOADED}
    end

    U->>API: POST .../commit
    API->>DB: verify all 10 files UPLOADED, set COMMITTED,\ninsert outbox row (1 tx)
    API-->>U: 200 {status: COMMITTED}

    Worker->>DB: SELECT ... FOR UPDATE SKIP LOCKED\n(holds lock across publish)
    Worker->>PS: publish submission.committed
    PS-->>Worker: ack
    Worker->>DB: outbox row → published_at set
```

## Failure: upload verify fails, client retries

Covers both sub-cases `/complete` treats as the same outcome: the object never landed in GCS (`404`-equivalent `notFound()`) and the object landed but at the wrong size. Either way the file — and only that file — goes to `FAILED`; the other 9 are untouched and the submission stays `PENDING`.

```mermaid
sequenceDiagram
    actor U as Browser
    participant API as API pod
    participant DB as Postgres
    participant GCS

    U->>API: POST .../files/{fileId}/upload-credentials
    API-->>U: signed PUT URL
    Note over U,GCS: Upload never completes, or client\nuploads truncated/wrong bytes
    U->>API: POST .../files/{fileId}/complete
    API->>GCS: verify(objectPath)
    alt object missing
        GCS-->>API: not found
    else object present, wrong size
        GCS-->>API: size=X (declared=Y, X≠Y)
    end
    API->>DB: file → FAILED (short tx)
    API-->>U: 422 Unprocessable Entity

    Note over U: Client re-issues credentials\nfor the same file and retries
    U->>API: POST .../files/{fileId}/upload-credentials
    API->>DB: file FAILED → UPLOADING (allowed transition)
    API-->>U: new signed PUT URL
    U->>GCS: PUT file bytes (correct this time)
    U->>API: POST .../files/{fileId}/complete
    API->>GCS: verify(objectPath)
    GCS-->>API: size matches
    API->>DB: file → UPLOADED
    API-->>U: 200 {status: UPLOADED}
```

## Failure: commit attempted with files not all uploaded

Commit does no I/O and touches no file rows on this path — the submission is exactly as re-triable after a 409 as it was before the attempt.

```mermaid
sequenceDiagram
    actor U as Browser
    participant API as API pod
    participant DB as Postgres

    U->>API: POST .../commit
    API->>DB: SELECT ... FOR UPDATE
    API->>DB: load all 10 files
    Note over API: file 7 is still PENDING\n(never uploaded)
    API-->>U: 409 Conflict\n(no rows written, submission stays PENDING)

    Note over U: Client uploads the missing file,\nthen commits again
    U->>API: POST .../files/{file7}/upload-credentials
    U->>API: PUT bytes, then complete
    U->>API: POST .../commit
    API->>DB: all 10 now UPLOADED
    API-->>U: 200 {status: COMMITTED}
```

## Edge case: concurrent double commit

Two callers race the same submission's commit. Correctness comes from the Postgres row lock, not application logic — the second caller blocks until the first's transaction commits, then sees `COMMITTED` and no-ops.

```mermaid
sequenceDiagram
    actor A as Caller A
    actor B as Caller B
    participant DB as Postgres

    par
        A->>DB: POST /commit → SELECT ... FOR UPDATE
        activate DB
        Note over DB: A holds the row lock
    and
        B->>DB: POST /commit → SELECT ... FOR UPDATE
        Note over B: B blocks, waiting for the lock
    end
    A->>DB: verify all UPLOADED, set COMMITTED,\ninsert 1 outbox row, commit tx
    deactivate DB
    A-->>A: 200 {status: COMMITTED, committedAt: T}

    Note over DB: lock released, B proceeds
    B->>DB: SELECT sees status=COMMITTED already
    B-->>B: 200 {status: COMMITTED, committedAt: T}\n(same value, no second outbox row)
```

## Failure: submission abandoned, reaper expires it

Runs as a `@Profile("reaper")` k8s CronJob, not `@Scheduled` inside the API pods — a one-shot process that exits when done. Re-locks and re-checks status before writing, so a submission the client legitimately committed in the window between the reaper's read and its write is never clobbered back to `EXPIRED`.

```mermaid
sequenceDiagram
    participant Reaper as Reaper CronJob
    participant DB as Postgres
    participant GCS

    Note over Reaper: submission has been PENDING\npast its 24h expires_at
    Reaper->>DB: find PENDING submissions\npast their expires_at
    loop for each staged file
        Reaper->>GCS: delete staged object [no tx open]
        alt delete fails
            Note over Reaper: logged and left for the bucket's 7-day lifecycle rule as backstop\n(doesn't block expiring the submission)
        end
    end
    Reaper->>DB: findByIdForUpdate (re-lock, re-check status)
    alt still PENDING
        Reaper->>DB: submission → EXPIRED
    else client committed it in the meantime
        Note over Reaper: no-op — commit already won the race
    end
```

## Local GCP emulation topology

The `docker-compose.yml` stack used to exercise the real `XmlMultipartTransport`/`PubSubOutboxPublisher` code without a GKE cluster or GCP account (see [architecture.md](architecture.md#local-gcp-emulation-no-gkegcp-account-needed)). The app itself runs on the host (`mvn spring-boot:run`), not inside compose, for fast dev iteration.

```mermaid
flowchart LR
    subgraph Host["Host machine"]
        App["Spring Boot app\n(mvn spring-boot:run)\ntransport=xml-multipart\npublisher=pubsub"]
        Browser2["Browser\n(frontend, real mode)"]
    end

    subgraph Compose["docker-compose.yml"]
        PG2[("postgres:16")]
        FGS["fake-gcs-server\n:4443, plain HTTP"]
        PSE["gcloud pubsub emulator\n:8085"]
    end

    App -- "NoCredentials +\nauto-create bucket" --> FGS
    App -- "in-memory RSA signer\nfor signUrl()" --> FGS
    App -- "NoCredentialsProvider +\nauto-create topic" --> PSE
    App --> PG2
    Browser2 -- "signed PUT (http://localhost:4443/...)" --> FGS
    Browser2 -- "JSON API" --> App
```
