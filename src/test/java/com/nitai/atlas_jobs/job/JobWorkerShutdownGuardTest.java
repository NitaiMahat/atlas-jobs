package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobWorkerShutdownGuardTest extends AbstractPostgresTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobWorker jobWorker;

    @Autowired
    WorkerShutdownLatch shutdownLatch;

    @Test
    void pollAndExecuteOneDoesNotClaimWhenShuttingDown() {
        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                "SLEEP_JOB",
                "{\"sleepSeconds\": 0}",
                1,
                null
        );
        jobRepository.saveAndFlush(job);

        shutdownLatch.signalShutdown();
        jobWorker.pollAndExecuteOne();

        Job refreshed = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(refreshed.getWorkerId()).isNull();
        assertThat(refreshed.getStartedAt()).isNull();
    }
}