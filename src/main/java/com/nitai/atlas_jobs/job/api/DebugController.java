package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobRepository;
import com.nitai.atlas_jobs.job.JobStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/debug")
public class DebugController {

    private final JobRepository jobRepository;

    public DebugController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }


    @GetMapping("/workers")
    public Map<String, Map<JobStatus, Long>> workerStats(
            @RequestParam(value = "sinceMinutes", required = false) Integer sinceMinutes
    ) {
        List<WorkerJobCount> rows = (sinceMinutes == null)
                ? jobRepository.countJobsByWorkerAndStatus()
                : jobRepository.countJobsByWorkerAndStatusSince(sinceMinutes);

        Map<String, Map<JobStatus, Long>> result = new LinkedHashMap<>();
        for (WorkerJobCount row : rows) {
            result
                    .computeIfAbsent(row.getWorkerId(), k -> new EnumMap<>(JobStatus.class))
                    .put(row.getStatus(), row.getCount());
        }
        return result;
    }



    @GetMapping("/workers/summary")
    public Map<JobStatus, Long> summary(
            @RequestParam(name = "sinceMinutes", required = false) Integer sinceMinutes
    ) {
        List<Object[]> rows = (sinceMinutes == null)
                ? jobRepository.countJobsByStatus()
                : jobRepository.countJobsByStatusSince(sinceMinutes);

        Map<JobStatus, Long> result = new EnumMap<>(JobStatus.class);
        for (Object[] row : rows) {
            JobStatus status = JobStatus.valueOf(row[0].toString());
            long count = ((Number) row[1]).longValue();
            result.put(status, count);
        }
        return result;
    }
}
