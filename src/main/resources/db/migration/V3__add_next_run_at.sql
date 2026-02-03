ALTER TABLE jobs
    ADD COLUMN next_run_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX ix_jobs_status_next_run
    ON jobs(status, next_run_at);
