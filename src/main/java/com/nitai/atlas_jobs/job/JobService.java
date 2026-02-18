package com.nitai.atlas_jobs.job;

import org.springframework.transaction.annotation.Transactional;
import com.nitai.atlas_jobs.job.api.CreateJobRequest;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import java.util.List;
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
    @Transactional
    public Job requeueDeadLetter(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        if (job.getStatus() != JobStatus.DEAD_LETTERED) {
            throw new JobNotDeadLetteredException(jobId, job.getStatus());
        }

        job.requeueFromDeadLetter();
        return jobRepository.save(job);
    }
    @Transactional
    public int requeueDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        List<Job> jobs = jobRepository.findByStatusOrderByUpdatedAtAsc(
                JobStatus.DEAD_LETTERED,
                PageRequest.of(0, safeLimit)
        );

        for (Job job : jobs) {
            job.requeueFromDeadLetter();
        }

        jobRepository.saveAll(jobs);
        return jobs.size();
    }

}
