package com.nitai.atlas_jobs.job;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;

    @Column(name = "job_type", nullable = false, length = 64)
    private String jobType;

    @Column(name = "payload")
    private String payload;


    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "idempotency_key", length = 128, unique = false)
    private String idempotencyKey;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Job() {

    }

    public Job(UUID jobId, JobStatus status, String jobType, String payload, int maxAttempts, String idempotencyKey) {
        this.jobId = jobId;
        this.status = status;
        this.jobType = jobType;
        this.payload = payload;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    // Getters
    public UUID getJobId() { return jobId; }
    public JobStatus getStatus() { return status; }
    public String getJobType() { return jobType; }
    public String getPayload() { return payload; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // Small helpers
    public void markRunning() {
        this.status = JobStatus.RUNNING;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markSucceeded() {
        this.status = JobStatus.SUCCEEDED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markFailed(String error) {
        this.status = JobStatus.FAILED;
        this.lastError = error;
        this.updatedAt = OffsetDateTime.now();
    }
}
