package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import com.nitai.atlas_jobs.job.api.CreateJobRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobPayloadValidationTest extends AbstractPostgresTest {

    @Autowired
    JobService jobService;

    @Test
    void createJobRejectsInvalidSleepPayload() {
        CreateJobRequest request = new CreateJobRequest();
        request.setJobType("SLEEP_JOB");
        request.setPayload("{\"sleepSeconds\": 0}");

        assertThatThrownBy(() -> jobService.createJob(request, null))
                .isInstanceOf(InvalidJobPayloadException.class);
    }

    @Test
    void createJobRejectsInvalidJson() {
        CreateJobRequest request = new CreateJobRequest();
        request.setJobType("SLEEP_JOB");
        request.setPayload("{not-json}");

        assertThatThrownBy(() -> jobService.createJob(request, null))
                .isInstanceOf(InvalidJobPayloadException.class);
    }
}