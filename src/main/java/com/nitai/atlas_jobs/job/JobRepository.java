package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.job.api.WorkerJobCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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




}
