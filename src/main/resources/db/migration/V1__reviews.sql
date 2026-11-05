CREATE TABLE reviews (
    id              BIGSERIAL PRIMARY KEY,
    repo            TEXT        NOT NULL,
    pr_number       INTEGER     NOT NULL,
    head_sha        TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('QUEUED', 'POSTED', 'FAILED', 'SKIPPED')),
    provider        TEXT        NOT NULL,
    model           TEXT        NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    latency_ms      BIGINT,
    comments_posted INTEGER,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT reviews_pr_sha_unique UNIQUE (repo, pr_number, head_sha)
);

CREATE INDEX reviews_created_at_idx ON reviews (created_at DESC);
