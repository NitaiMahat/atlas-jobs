package com.nitai.atlas_jobs.job;


import com.nitai.atlas_jobs.AbstractPostgresTest;
import com.nitai.atlas_jobs.job.api.CreateJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class JobServiceTest extends AbstractPostgresTest {

    @Autowired
    JobService jobService;

    @Autowired
    JobRepository jobRepository;

    @Test
    void sameIdempotencyKeyReturnsSameJobAndOnlyOneRowInDb() {
        String idempotencyKey = "idem-key-1";
        CreateJobRequest request = new CreateJobRequest();
        request.setJobType("SLEEP_JOB");
        request.setPayload("{\"sleepSeconds\": 1}");
        request.setMaxAttempts(3);

        Job first = jobService.createJob(request, idempotencyKey);
        Job second = jobService.createJob(request, idempotencyKey);

        assertThat(second.getJobId()).isEqualTo(first.getJobId());
        assertThat(jobRepository.findByIdempotencyKey(idempotencyKey))
                .isPresent()
                .get()
                .extracting(Job::getJobId)
                .isEqualTo(first.getJobId());
    }
}