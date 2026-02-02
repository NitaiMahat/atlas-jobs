CREATE TABLE jobs (
                      job_id UUID PRIMARY KEY,
                      status VARCHAR(32) NOT NULL,
                      job_type VARCHAR(64) NOT NULL,
                      payload JSONB,
                      attempt_count INT NOT NULL DEFAULT 0,
                      max_attempts INT NOT NULL DEFAULT 3,
                      idempotency_key VARCHAR(128),
                      last_error TEXT,
                      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_jobs_idempotency_key
    ON jobs(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_jobs_status_created_at
    ON jobs(status, created_at);
