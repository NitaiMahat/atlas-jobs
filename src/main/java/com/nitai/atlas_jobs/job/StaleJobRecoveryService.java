package com.nitai.atlas_jobs.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class StaleJobRecoveryService {

    private final JobRepository jobRepository;
    private final JobMetrics jobMetrics;

    public StaleJobRecoveryService(JobRepository jobRepository, JobMetrics jobMetrics) {
        this.jobRepository = jobRepository;
        this.jobMetrics = jobMetrics;
    }

    @Scheduled(fixedDelayString = "${atlas.jobs.stale-recovery-interval-ms:60000}")
    @Transactional
    public void recoverStaleRunningJobs() {
        List<Job> stale = jobRepository.lockExpiredRunningJobs();

        for (Job job : stale) {
            job.onFailureAndScheduleRetry("Stale RUNNING (timeout)");
        }

        jobRepository.saveAllAndFlush(stale);
        jobMetrics.recordExpiredLeases(stale.size());
    }
}
