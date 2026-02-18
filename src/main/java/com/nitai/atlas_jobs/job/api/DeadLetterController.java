package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.JobService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DeadLetterController {

    private final JobService jobService;

    public DeadLetterController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/dead-letter/retry")
    public Map<String, Object> retryDeadLetters(@RequestParam(defaultValue = "100") int limit) {
        int count = jobService.requeueDeadLetters(limit);
        return Map.of("count", count);
    }
}