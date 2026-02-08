package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobStatus;

public record WorkerJobCount(
        String workerId,
        JobStatus status,
        long count
) {}
