package com.nitai.atlas_jobs.job;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.util.Optional;
import java.time.ZoneOffset;

@Service
public class JobClaimService {

    private final JobRepository jobRepository;
    private final String workerId;
    private final int runTimeoutMinutes;

    public JobClaimService(JobRepository jobRepository, String workerId,
                           @Value("${atlas.jobs.run-timeout-minutes:15}") int runTimeoutMinutes) {
        this.jobRepository = jobRepository;
        this.workerId = workerId;
        this.runTimeoutMinutes = runTimeoutMinutes;
    }


    @Transactional
    public Optional<Job> claimNextJob() {
        Optional<Job> maybeJob = jobRepository.claimNextQueuedJob();
        if (maybeJob.isEmpty()) return Optional.empty();

        Job job = maybeJob.get();
        job.markRunning(workerId, runTimeoutMinutes, jobRepository.databaseTime().atOffset(ZoneOffset.UTC));
        jobRepository.saveAndFlush(job);
        return Optional.of(job);
    }
}
