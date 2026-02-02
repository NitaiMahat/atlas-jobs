package com.nitai.atlas_jobs.job;


import com.nitai.atlas_jobs.job.api.CreateJobRequest;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public Job createJob(CreateJobRequest request, String idempotencyKey) {
        // If idempotency key is provided, return existing job if present
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Job> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        int maxAttempts = (request.getMaxAttempts() == null) ? 3 : request.getMaxAttempts();

        Job job = new Job(
                UUID.randomUUID(),
                JobStatus.QUEUED,
                request.getJobType(),
                request.getPayload(),
                maxAttempts,
                (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey
        );

        return jobRepository.save(job);
    }

    public Job getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new com.nitai.atlas_jobs.job.JobNotFoundException(jobId));
    }
}
