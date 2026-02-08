package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobRepository;
import com.nitai.atlas_jobs.job.JobStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/debug")
public class DebugController {

    private final JobRepository jobRepository;

    public DebugController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Example response shape:
     * {
     *   "worker-8080": { "SUCCEEDED": 12, "DEAD_LETTERED": 1 },
     *   "worker-8081": { "SUCCEEDED": 9 }
     * }
     */
    @GetMapping("/workers")
    public Map<String, Map<JobStatus, Long>> workerStats() {
        List<WorkerJobCount> rows = jobRepository.countJobsByWorkerAndStatus();

        Map<String, Map<JobStatus, Long>> result = new LinkedHashMap<>();
        for (WorkerJobCount row : rows) {
            result
                    .computeIfAbsent(row.workerId(), k -> new EnumMap<>(JobStatus.class))
                    .put(row.status(), row.count());
        }
        return result;
    }

    /**
     * Example response:
     * { "QUEUED": 3, "RUNNING": 0, "SUCCEEDED": 21, "DEAD_LETTERED": 1 }
     */
    @GetMapping("/workers/summary")
    public Map<JobStatus, Long> summary() {
        List<Object[]> rows = jobRepository.countJobsByStatus();

        Map<JobStatus, Long> result = new EnumMap<>(JobStatus.class);
        for (Object[] row : rows) {
            JobStatus status = (JobStatus) row[0];
            Long count = (Long) row[1];
            result.put(status, count);
        }
        return result;
    }
}
