package com.nitai.atlas_jobs.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JobWorker {

    private final JobClaimService jobClaimService;
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;

    public JobWorker(JobClaimService jobClaimService,
                     JobRepository jobRepository,
                     JobExecutor jobExecutor) {
        this.jobClaimService = jobClaimService;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void pollAndExecuteOne() {
        var maybeJob = jobClaimService.claimNextJob();
        if (maybeJob.isEmpty()) return;

        Job job = maybeJob.get();

        try {
            jobExecutor.execute(job);
            job.markSucceeded();
        } catch (Exception e) {
            job.onFailureAndScheduleRetry(e.getMessage());
        }

        jobRepository.saveAndFlush(job);
    }

}
