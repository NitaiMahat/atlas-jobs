package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobMetrics;
import com.nitai.atlas_jobs.job.JobRepository;
import com.nitai.atlas_jobs.job.JobStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
public class MetricsController {

    private final JobRepository jobRepository;
    private final JobMetrics jobMetrics;

    public MetricsController(JobRepository jobRepository, JobMetrics jobMetrics) {
        this.jobRepository = jobRepository;
        this.jobMetrics = jobMetrics;
    }

    @GetMapping("/metrics")
    public MetricsResponse metrics(@RequestParam(name = "sinceMinutes", required = false, defaultValue = "5") int sinceMinutes) {
        // Global counts by status
        Map<JobStatus, Long> statusCounts = toStatusMap(jobRepository.countJobsByStatus());

        // Counts by worker + status
        Map<String, Map<JobStatus, Long>> byWorker = toWorkerMap(jobRepository.countJobsByWorkerAndStatus());

        // Attempt distribution
        Map<Integer, Long> attemptDist = toAttemptMap(jobRepository.countJobsByAttemptCount());

        // Scheduled retries
        long scheduledForRetry = jobRepository.countScheduledForRetry();

        // Recent window
        Map<JobStatus, Long> recentStatus = toStatusMap(jobRepository.countJobsByStatusUpdatedSince(sinceMinutes));
        Map<String, Map<JobStatus, Long>> recentByWorker = toWorkerMap(jobRepository.countJobsByWorkerAndStatusUpdatedSince(sinceMinutes));

        MetricsResponse.MetricsWindow recent = new MetricsResponse.MetricsWindow(
                sinceMinutes,
                recentStatus,
                recentByWorker
        );

        long processedLastMinute = jobMetrics.getProcessedLastMinute();
        Map<String, Long> failuresByJobType = jobMetrics.getFailuresByJobType();
        Map<String, Double> avgDurationSecondsByJobType = jobMetrics.getAvgDurationSecondsByJobType();

        return new MetricsResponse(
                statusCounts,
                byWorker,
                attemptDist,
                scheduledForRetry,
                recent,
                processedLastMinute,
                failuresByJobType,
                avgDurationSecondsByJobType
        );
    }

    private Map<JobStatus, Long> toStatusMap(List<Object[]> rows) {
        Map<JobStatus, Long> result = new EnumMap<>(JobStatus.class);
        for (Object[] row : rows) {
            JobStatus status = JobStatus.valueOf(row[0].toString());
            long count = ((Number) row[1]).longValue();
            result.put(status, count);
        }
        return result;
    }

    private Map<Integer, Long> toAttemptMap(List<Object[]> rows) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            int attemptCount = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            result.put(attemptCount, count);
        }
        return result;
    }

    private Map<String, Map<JobStatus, Long>> toWorkerMap(List<WorkerJobCount> rows) {
        Map<String, Map<JobStatus, Long>> result = new LinkedHashMap<>();
        for (WorkerJobCount row : rows) {
            result
                    .computeIfAbsent(row.getWorkerId(), k -> new EnumMap<>(JobStatus.class))
                    .put(row.getStatus(), row.getCount());
        }
        return result;
    }
}