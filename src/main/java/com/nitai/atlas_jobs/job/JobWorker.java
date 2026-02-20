package com.nitai.atlas_jobs.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class JobWorker {
    private final WorkerShutdownLatch shutdownLatch;
    private final JobClaimService jobClaimService;
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;
    private final JobMetrics jobMetrics;

    public JobWorker(JobClaimService jobClaimService,
                     JobRepository jobRepository,
                     JobExecutor jobExecutor,
                     WorkerShutdownLatch shutdownLatch,
                     JobMetrics jobMetrics) {
        this.jobClaimService = jobClaimService;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
        this.shutdownLatch = shutdownLatch;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(fixedDelay = 2000)
    public void pollAndExecuteOne() {
        if (shutdownLatch.isShuttingDown()) return;
        var maybeJob = jobClaimService.claimNextJob();
        if (maybeJob.isEmpty()) return;

        Job job = maybeJob.get();
        long startNanos = System.nanoTime();

        try {
            jobExecutor.execute(job);
            completeSuccess(job.getJobId());
            jobMetrics.recordSuccess(job.getJobType(), System.nanoTime() - startNanos);
        } catch (Exception e) {
            completeFailure(job.getJobId(), e.getMessage());
            jobMetrics.recordFailure(job.getJobType(), System.nanoTime() - startNanos);
        }
    }

    @Transactional
    public void completeSuccess(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.RUNNING) return;
        job.markSucceeded();
        jobRepository.saveAndFlush(job);
    }

    @Transactional
    public void completeFailure(UUID jobId, String error) {
        Job job = jobRepository.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.RUNNING) return;
        job.onFailureAndScheduleRetry(error);
        jobRepository.saveAndFlush(job);
    }
}