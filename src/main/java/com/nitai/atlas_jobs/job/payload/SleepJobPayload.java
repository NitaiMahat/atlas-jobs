package com.nitai.atlas_jobs.job.payload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class SleepJobPayload {

    @Min(1)
    @Max(300)
    private Integer sleepSeconds;

    public SleepJobPayload() {}

    public Integer getSleepSeconds() { return sleepSeconds; }
    public void setSleepSeconds(Integer sleepSeconds) { this.sleepSeconds = sleepSeconds; }
}