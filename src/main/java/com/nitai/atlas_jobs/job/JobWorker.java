package com.nitai.atlas_jobs.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class JobWorker {
    private final WorkerShutdownLatch shutdownLatch;
    private final JobClaimService jobClaimService;
    private final JobCompletionService completionService;
    private final JobExecutor jobExecutor;
    private final JobMetrics jobMetrics;
    private final boolean workerEnabled;

    public JobWorker(JobClaimService jobClaimService,
                     JobCompletionService completionService,
                     JobExecutor jobExecutor,
                     WorkerShutdownLatch shutdownLatch,
                     JobMetrics jobMetrics,
                     @Value("${atlas.jobs.worker-enabled:true}") boolean workerEnabled) {
        this.jobClaimService = jobClaimService;
        this.completionService = completionService;
        this.jobExecutor = jobExecutor;
        this.shutdownLatch = shutdownLatch;
        this.jobMetrics = jobMetrics;
        this.workerEnabled = workerEnabled;
    }

    @Scheduled(fixedDelay = 2000)
    public void pollAndExecuteOne() {
        if (!workerEnabled || shutdownLatch.isShuttingDown()) return;
        var maybeJob = jobClaimService.claimNextJob();
        if (maybeJob.isEmpty()) return;

        Job job = maybeJob.get();
        long startNanos = System.nanoTime();

        try {
            jobExecutor.execute(job);
        } catch (Exception e) {
            if (completionService.completeFailure(job.getJobId(), job.getWorkerId(), job.getLeaseGeneration(), e.getMessage())) {
                jobMetrics.recordFailure(job.getJobType(), System.nanoTime() - startNanos);
            } else {
                jobMetrics.recordStaleCompletionRejection();
            }
            return;
        }

        if (completionService.completeSuccess(job.getJobId(), job.getWorkerId(), job.getLeaseGeneration())) {
            jobMetrics.recordSuccess(job.getJobType(), System.nanoTime() - startNanos);
        } else {
            jobMetrics.recordStaleCompletionRejection();
        }
    }
}
