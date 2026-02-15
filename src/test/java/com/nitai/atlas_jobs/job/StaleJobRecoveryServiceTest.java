package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaleJobRecoveryServiceTest extends AbstractPostgresTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    StaleJobRecoveryService staleJobRecoveryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void staleRunningJobIsRequeued() {
        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                "SLEEP_JOB",
                "{\"sleepSeconds\": 0}",
                3,
                null
        );
        jobRepository.saveAndFlush(job);

        OffsetDateTime stale = OffsetDateTime.now().minusMinutes(30);
        Timestamp ts = Timestamp.from(stale.toInstant());

        jdbcTemplate.update(
                "UPDATE jobs " +
                        "SET status = 'RUNNING', started_at = ?, updated_at = ?, attempt_count = 0, max_attempts = 3, worker_id = ? " +
                        "WHERE job_id = ?",
                ts, ts, "worker-1", job.getJobId()
        );

        staleJobRecoveryService.recoverStaleRunningJobs();

        Job updated = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getLastError()).isEqualTo("Stale RUNNING (timeout)");
        assertThat(updated.getNextRunAt()).isAfter(updated.getUpdatedAt());
    }
}