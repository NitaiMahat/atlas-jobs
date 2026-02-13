package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobRetryAndDeadLetterTest extends AbstractPostgresTest {

    @Autowired
    JobRepository jobRepository;

    @Test
    void afterOneFailure_statusIsQueuedAndNextRunAtInFuture() {
        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                "FAIL_JOB",
                null,
                3,
                null
        );
        job = jobRepository.saveAndFlush(job);

        job.onFailureAndScheduleRetry("first failure");

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getNextRunAt()).isAfter(OffsetDateTime.now());
        assertThat(job.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void afterMaxAttempts_statusIsDeadLettered() {
        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                "FAIL_JOB",
                null,
                3,
                null
        );
        job = jobRepository.saveAndFlush(job);

        for (int i = 0; i < 3; i++) {
            job.onFailureAndScheduleRetry("attempt " + (i + 1));
            jobRepository.saveAndFlush(job);
            if (job.getStatus() == JobStatus.DEAD_LETTERED) break;
            job = jobRepository.findById(job.getJobId()).orElseThrow();
        }

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTERED);
        assertThat(job.getAttemptCount()).isEqualTo(3);
    }
}