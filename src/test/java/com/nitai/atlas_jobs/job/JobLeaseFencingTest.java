package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobLeaseFencingTest extends AbstractPostgresTest {
    @Autowired JobRepository jobs;
    @Autowired JobCompletionService completions;
    @Autowired StaleJobRecoveryService recovery;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @Test
    void ownedFailureDeadLettersAtAttemptLimit() {
        Job job = jobs.saveAndFlush(new Job(UUID.randomUUID(), JobStatus.QUEUED,
                "FAIL_JOB", null, 1, null));
        jdbc.update("UPDATE jobs SET next_run_at = now() - interval '1 second' WHERE job_id = ?", job.getJobId());
        JobClaimService worker = new JobClaimService(jobs, "worker-f", 15);
        Job claim = transactions.execute(ignored -> worker.claimNextJob().orElseThrow());
        assertThat(claim.getJobId()).isEqualTo(job.getJobId());

        assertThat(completions.completeFailure(job.getJobId(), "worker-f",
                claim.getLeaseGeneration(), "intentional")).isTrue();
        Job finished = jobs.findById(job.getJobId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(JobStatus.DEAD_LETTERED);
        assertThat(finished.getAttemptCount()).isEqualTo(1);
        assertThat(finished.getLastError()).isEqualTo("intentional");
    }

    @Test
    void expiredWorkerCannotFinishAfterWorkerIdIsReused() {
        Job original = jobs.saveAndFlush(new Job(UUID.randomUUID(), JobStatus.QUEUED,
                "SLEEP_JOB", "{\"sleepSeconds\": 1}", 3, null));
        jdbc.update("UPDATE jobs SET next_run_at = now() - interval '1 second' WHERE job_id = ?", original.getJobId());
        JobClaimService workerA = new JobClaimService(jobs, "worker-a", 15);
        JobClaimService workerB = new JobClaimService(jobs, "worker-a", 15);

        Job firstClaim = transactions.execute(ignored -> workerA.claimNextJob().orElseThrow());
        assertThat(firstClaim.getJobId()).isEqualTo(original.getJobId());
        long oldGeneration = firstClaim.getLeaseGeneration();
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 second' WHERE job_id = ?",
                original.getJobId());

        assertThat(completions.completeSuccess(original.getJobId(), "worker-a", oldGeneration)).isFalse();
        recovery.recoverStaleRunningJobs();
        jdbc.update("UPDATE jobs SET next_run_at = now() - interval '1 second' WHERE job_id = ?",
                original.getJobId());

        Job secondClaim = transactions.execute(ignored -> workerB.claimNextJob().orElseThrow());
        assertThat(secondClaim.getLeaseGeneration()).isEqualTo(oldGeneration + 1);
        assertThat(completions.completeSuccess(original.getJobId(), "worker-a", oldGeneration)).isFalse();
        assertThat(completions.completeFailure(original.getJobId(), "worker-a", oldGeneration, "late failure")).isFalse();

        Job stillOwnedByB = jobs.findById(original.getJobId()).orElseThrow();
        assertThat(stillOwnedByB.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(stillOwnedByB.getWorkerId()).isEqualTo("worker-a");
        assertThat(stillOwnedByB.getAttemptCount()).isEqualTo(1);
        assertThat(stillOwnedByB.getLastError()).isEqualTo("Stale RUNNING (timeout)");
        assertThat(completions.completeSuccess(original.getJobId(), "worker-a", secondClaim.getLeaseGeneration())).isTrue();
        assertThat(jobs.findById(original.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }
}
