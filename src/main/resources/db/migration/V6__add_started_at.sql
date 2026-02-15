ALTER TABLE jobs ADD COLUMN started_at TIMESTAMPTZ;

--backfill for existing RUNNING rows
UPDATE jobs
SET started_at = updated_at
WHERE started_at IS NULL AND status = 'RUNNING';

--index for recovery queries
CREATE INDEX ix_jobs_status_started_at
    ON jobs(status, started_at)
    WHERE status = 'RUNNING';