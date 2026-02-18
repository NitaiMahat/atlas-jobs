package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetterRequeueTest extends AbstractPostgresTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobService jobService;

    @Test
    void requeueDeadLetteredJob() {
        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                "FAIL_JOB",
                null,
                3,
                null
        );
        jobRepository.saveAndFlush(job);

        for (int i = 0; i < job.getMaxAttempts(); i++) {
            job.onFailureAndScheduleRetry("attempt " + (i + 1));
        }
        jobRepository.saveAndFlush(job);

        OffsetDateTime before = OffsetDateTime.now();
        Job requeued = jobService.requeueDeadLetter(job.getJobId());
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.getNextRunAt()).isAfterOrEqualTo(before);
        assertThat(requeued.getNextRunAt()).isBeforeOrEqualTo(after);


        assertThat(requeued.getAttemptCount()).isEqualTo(0);
        assertThat(requeued.getLastError()).isNull();
    }

    @Test
    void requeueNonDeadLetteredJob_throws() {
        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                "ANY_JOB",
                null,
                3,
                null
        );
        jobRepository.saveAndFlush(job);

        assertThatThrownBy(() -> jobService.requeueDeadLetter(job.getJobId()))
                .isInstanceOf(JobNotDeadLetteredException.class);
    }
}