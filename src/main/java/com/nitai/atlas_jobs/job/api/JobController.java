package com.nitai.atlas_jobs.job.api;

import com.nitai.atlas_jobs.job.Job;
import com.nitai.atlas_jobs.job.JobNotFoundException;
import com.nitai.atlas_jobs.job.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        Job job = jobService.createJob(request, idempotencyKey);
        return JobResponse.from(job);
    }

    @GetMapping("/{jobId}")
    public JobResponse getJob(@PathVariable UUID jobId) {
        Job job = jobService.getJob(jobId);
        return JobResponse.from(job);
    }

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(JobNotFoundException ex) {
        return ex.getMessage();
    }
}
