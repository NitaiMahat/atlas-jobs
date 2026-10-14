package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.job.api.WorkerJobCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    List<Job> findAllByOrderByCreatedAtDesc(Pageable pageable);

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
    @Query(value = """
        SELECT * FROM jobs
        WHERE status = 'RUNNING' AND lease_expires_at <= clock_timestamp()
        ORDER BY lease_expires_at
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Job> lockExpiredRunningJobs();

    @Query(value = "SELECT * FROM jobs WHERE job_id = :jobId FOR UPDATE", nativeQuery = true)
    Optional<Job> lockById(@Param("jobId") UUID jobId);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Modifying
    @Query(value = """
        UPDATE jobs
        SET status = 'SUCCEEDED', updated_at = now(), lease_expires_at = NULL
        WHERE job_id = :jobId AND status = 'RUNNING'
          AND worker_id = :workerId AND lease_generation = :generation
          AND lease_expires_at > clock_timestamp()
        """, nativeQuery = true)
    int completeOwnedJob(@Param("jobId") UUID jobId,
                         @Param("workerId") String workerId,
                         @Param("generation") long generation);

    @Query(
            value = """
                SELECT status, COUNT(*)
                FROM jobs
                GROUP BY status
            """,
            nativeQuery = true
    )
    List<Object[]> countJobsByStatus();

    @Query(
            value = """
            SELECT
              COALESCE(worker_id, 'unassigned') AS workerId,
              status AS status,
              COUNT(*) AS count
            FROM jobs
            GROUP BY COALESCE(worker_id, 'unassigned'), status
            ORDER BY workerId, status
        """,
            nativeQuery = true
    )
    List<WorkerJobCount> countJobsByWorkerAndStatus();


    @Query(
            value = """
                SELECT status, COUNT(*)
                FROM jobs
                WHERE created_at >= now() - make_interval(mins => :sinceMinutes)
                GROUP BY status
            """,
            nativeQuery = true
    )
    List<Object[]> countJobsByStatusSince(@Param("sinceMinutes") int sinceMinutes);

    @Query(
            value = """
            SELECT
              COALESCE(worker_id, 'unassigned') AS workerId,
              status AS status,
              COUNT(*) AS count
            FROM jobs
            WHERE created_at >= now() - make_interval(mins => :sinceMinutes)
            GROUP BY COALESCE(worker_id, 'unassigned'), status
            ORDER BY workerId, status
        """,
            nativeQuery = true
    )
    List<WorkerJobCount> countJobsByWorkerAndStatusSince(@Param("sinceMinutes") int sinceMinutes);



    @Query(
            value = """
            SELECT attempt_count, COUNT(*)
            FROM jobs
            GROUP BY attempt_count
            ORDER BY attempt_count
        """,
            nativeQuery = true
    )
    List<Object[]> countJobsByAttemptCount();

    /**
     * Jobs that are QUEUED but not eligible yet (scheduled for the future).
     */
    @Query(
            value = """
            SELECT COUNT(*)
            FROM jobs
            WHERE status = 'QUEUED'
              AND next_run_at > now()
        """,
            nativeQuery = true
    )
    long countScheduledForRetry();

    @Query(value = """
        SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - MIN(GREATEST(created_at, next_run_at))), 0)::double precision
        FROM jobs
        WHERE status = 'QUEUED' AND next_run_at <= clock_timestamp()
        """, nativeQuery = true)
    double oldestReadyJobWaitSeconds();

    /**
     * Recent activity window using updated_at (best signal for "processed recently").
     */
    @Query(
            value = """
            SELECT status, COUNT(*)
            FROM jobs
            WHERE updated_at >= now() - make_interval(mins => :sinceMinutes)
            GROUP BY status
        """,
            nativeQuery = true
    )
    List<Object[]> countJobsByStatusUpdatedSince(@Param("sinceMinutes") int sinceMinutes);

    @Query(
            value = """
            SELECT
              COALESCE(worker_id, 'unassigned') AS workerId,
              status AS status,
              COUNT(*) AS count
            FROM jobs
            WHERE updated_at >= now() - make_interval(mins => :sinceMinutes)
            GROUP BY COALESCE(worker_id, 'unassigned'), status
            ORDER BY workerId, status
        """,
            nativeQuery = true
    )
    List<WorkerJobCount> countJobsByWorkerAndStatusUpdatedSince(@Param("sinceMinutes") int sinceMinutes);

    List<Job> findByStatusOrderByUpdatedAtAsc(JobStatus status, Pageable pageable);


}
