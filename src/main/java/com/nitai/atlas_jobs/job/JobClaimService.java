package com.nitai.atlas_jobs.job;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class JobClaimService {

    private final JobRepository jobRepository;
    private final String workerId;

    public JobClaimService(JobRepository jobRepository, String workerId) {
        this.jobRepository = jobRepository;
        this.workerId = workerId;
    }


    public Optional<Job> claimNextJob() {
        Optional<Job> maybeJob = jobRepository.claimNextQueuedJob();
        if (maybeJob.isEmpty()) return Optional.empty();

        Job job = maybeJob.get();
        job.markRunning(workerId);

        return Optional.of(job);
    }
}
