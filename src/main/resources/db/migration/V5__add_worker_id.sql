ALTER TABLE jobs
    ADD COLUMN worker_id VARCHAR(64);

CREATE INDEX ix_jobs_worker_id ON jobs(worker_id);
