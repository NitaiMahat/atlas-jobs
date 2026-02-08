package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.job.api.WorkerJobCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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

    // ✅ New: grouped counts per worker + status
    @Query("""
        select new com.nitai.atlas_jobs.job.api.WorkerJobCount(
            j.workerId,
            j.status,
            count(j)
        )
        from Job j
        where j.workerId is not null
        group by j.workerId, j.status
        order by j.workerId, j.status
    """)
    List<WorkerJobCount> countJobsByWorkerAndStatus();

    // ✅ New: global counts by status
    @Query("""
        select j.status, count(j)
        from Job j
        group by j.status
        order by j.status
    """)
    List<Object[]> countJobsByStatus();
}
