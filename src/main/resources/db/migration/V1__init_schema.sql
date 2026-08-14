CREATE TABLE submission (
    id             UUID PRIMARY KEY,
    owner_id       VARCHAR(255) NOT NULL,
    status         VARCHAR(20)  NOT NULL
                    CHECK (status IN ('PENDING','COMMITTED','EXPIRED','FAILED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    committed_at   TIMESTAMPTZ
);

-- reaper backstop (Step 7): cheap scan for PENDING submissions past expiry
CREATE INDEX idx_submission_pending_expires_at
    ON submission (expires_at) WHERE status = 'PENDING';

CREATE INDEX idx_submission_owner_id ON submission (owner_id);

CREATE TABLE submission_file (
    id              UUID PRIMARY KEY,
    submission_id   UUID NOT NULL REFERENCES submission(id),
    ordinal         INT  NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    object_path     VARCHAR(512) NOT NULL,
    declared_name   VARCHAR(255) NOT NULL,
    declared_size   BIGINT NOT NULL CHECK (declared_size > 0),
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('PENDING','UPLOADING','UPLOADED','FAILED')),
    gcs_generation  BIGINT,
    crc32c          VARCHAR(16),
    size_bytes      BIGINT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_submission_file_ordinal UNIQUE (submission_id, ordinal),
    CONSTRAINT uq_submission_file_object_path UNIQUE (object_path)
);

CREATE INDEX idx_submission_file_submission_id ON submission_file (submission_id);

CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  UUID NOT NULL,
    type          VARCHAR(100) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished
    ON outbox (published_at) WHERE published_at IS NULL;

CREATE INDEX idx_outbox_aggregate_id ON outbox (aggregate_id);
