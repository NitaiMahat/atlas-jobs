package com.nitai.atlas_jobs.job;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JobCompletionService {
    private final JobRepository jobRepository;

    public JobCompletionService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public boolean completeSuccess(UUID jobId, String workerId, long generation) {
        return jobRepository.completeOwnedJob(jobId, workerId, generation) == 1;
    }

    @Transactional
    public boolean completeFailure(UUID jobId, String workerId, long generation, String error) {
        Job job = jobRepository.lockById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.RUNNING
                || !workerId.equals(job.getWorkerId())
                || generation != job.getLeaseGeneration()
                || job.getLeaseExpiresAt() == null
                || !job.getLeaseExpiresAt().toInstant().isAfter(jobRepository.databaseTime())) {
            return false;
        }
        job.onFailureAndScheduleRetry(error);
        jobRepository.saveAndFlush(job);
        return true;
    }
}
