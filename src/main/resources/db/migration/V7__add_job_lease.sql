ALTER TABLE jobs
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- Existing running jobs keep the original 15-minute timeout on upgrade.
UPDATE jobs
SET lease_expires_at = COALESCE(started_at, updated_at) + INTERVAL '15 minutes'
WHERE status = 'RUNNING';

CREATE INDEX ix_jobs_expired_leases
    ON jobs(lease_expires_at)
    WHERE status = 'RUNNING';
