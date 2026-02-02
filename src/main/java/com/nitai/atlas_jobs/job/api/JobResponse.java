package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.Job;
import java.time.OffsetDateTime;
import java.util.UUID;

public class JobResponse {

    private UUID jobId;
    private String status;
    private String jobType;
    private int attemptCount;
    private int maxAttempts;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static JobResponse from(Job job) {
        JobResponse r = new JobResponse();
        r.jobId = job.getJobId();
        r.status = job.getStatus().name();
        r.jobType = job.getJobType();
        r.attemptCount = job.getAttemptCount();
        r.maxAttempts = job.getMaxAttempts();
        r.createdAt = job.getCreatedAt();
        r.updatedAt = job.getUpdatedAt();
        return r;
    }

    public UUID getJobId() { return jobId; }
    public String getStatus() { return status; }
    public String getJobType() { return jobType; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
