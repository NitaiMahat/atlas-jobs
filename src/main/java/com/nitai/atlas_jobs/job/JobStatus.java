package com.nitai.atlas_jobs.job;

public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,//Job failed intregation ongoing

    DEAD_LETTERED

}
