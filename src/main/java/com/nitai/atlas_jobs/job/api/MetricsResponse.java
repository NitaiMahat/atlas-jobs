package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobStatus;

import java.util.Map;

public record MetricsResponse(
        Map<JobStatus, Long> statusCounts,
        Map<String, Map<JobStatus, Long>> byWorker,
        Map<Integer, Long> attemptDistribution,
        long scheduledForRetry,
        MetricsWindow recent
) {
    public record MetricsWindow(
            int sinceMinutes,
            Map<JobStatus, Long> statusCounts,
            Map<String, Map<JobStatus, Long>> byWorker
    ) {}
}
