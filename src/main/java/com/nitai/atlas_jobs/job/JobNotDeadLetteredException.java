package com.nitai.atlas_jobs.job;

import java.util.UUID;

public class JobNotDeadLetteredException extends RuntimeException {
    public JobNotDeadLetteredException(UUID jobId, JobStatus status) {
        super("Job is not DEAD_LETTERED: " + jobId + " (status=" + status + ")");
    }
}