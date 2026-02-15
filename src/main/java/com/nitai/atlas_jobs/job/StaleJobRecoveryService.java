package com.nitai.atlas_jobs.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class StaleJobRecoveryService {

    private final JobRepository jobRepository;
    private final int runTimeoutMinutes;

    public StaleJobRecoveryService(
            JobRepository jobRepository,
            @Value("${atlas.jobs.run-timeout-minutes:15}") int runTimeoutMinutes
    ) {
        this.jobRepository = jobRepository;
        this.runTimeoutMinutes = runTimeoutMinutes;
    }

    @Scheduled(fixedDelayString = "${atlas.jobs.stale-recovery-interval-ms:60000}")
    @Transactional
    public void recoverStaleRunningJobs() {
        OffsetDateTime olderThan = OffsetDateTime.now().minusMinutes(runTimeoutMinutes);
        List<Job> stale = jobRepository.findStaleRunningJobs(olderThan);

        for (Job job : stale) {
            job.onFailureAndScheduleRetry("Stale RUNNING (timeout)");

        }

        jobRepository.saveAll(stale);
    }
}