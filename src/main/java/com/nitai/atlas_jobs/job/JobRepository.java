package com.nitai.atlas_jobs.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    Optional<Job> findByIdempotencyKey(String idempotencyKey);
    @Query(
            value = """
    SELECT *
    FROM jobs
    WHERE status = 'QUEUED'
      AND next_run_at <= now()
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
    """,
            nativeQuery = true
    )
    Optional<Job> claimNextQueuedJob();
}
