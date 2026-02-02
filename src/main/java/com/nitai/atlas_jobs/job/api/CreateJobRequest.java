package com.nitai.atlas_jobs.job.api;

import jakarta.validation.constraints.NotBlank;

public class CreateJobRequest {

    @NotBlank
    private String jobType;

    //payload is raw JSON string for now
    private String payload;


    private Integer maxAttempts;

    public CreateJobRequest() {}

    public String getJobType() { return jobType; }
    public String getPayload() { return payload; }
    public Integer getMaxAttempts() { return maxAttempts; }

    public void setJobType(String jobType) { this.jobType = jobType; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
}
