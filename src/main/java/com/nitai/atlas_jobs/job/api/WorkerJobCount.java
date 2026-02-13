package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobStatus;

public interface WorkerJobCount {
    String getWorkerId();
    JobStatus getStatus();
    Long getCount();
}
