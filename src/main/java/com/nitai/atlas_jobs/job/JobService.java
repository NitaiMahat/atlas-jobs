package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.job.api.CreateJobRequest;
import com.nitai.atlas_jobs.job.payload.PayloadParser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final PayloadParser payloadParser;

    public JobService(JobRepository jobRepository, PayloadParser payloadParser) {
        this.jobRepository = jobRepository;
        this.payloadParser = payloadParser;
    }

    public Job createJob(CreateJobRequest request, String idempotencyKey) {
        // If idempotency key is provided, return existing job if present
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Job> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        validatePayloadForType(request);

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

    private void validatePayloadForType(CreateJobRequest request) {
        String jobType = request.getJobType();
        switch (jobType) {
            case "SLEEP_JOB" -> payloadParser.parseSleepPayload(request.getPayload());
            case "FAIL_JOB" -> payloadParser.parseFailPayload(request.getPayload());
            default -> throw new InvalidJobPayloadException("Unknown job type: " + jobType);
        }
    }

    public Job getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
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