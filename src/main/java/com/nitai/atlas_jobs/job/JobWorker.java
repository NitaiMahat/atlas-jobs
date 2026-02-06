package com.nitai.atlas_jobs.job;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class JobWorker {
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;

    public JobWorker(JobRepository jobRepository, JobExecutor jobExecutor) {
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void pollAndExecuteOne() {
        jobRepository.claimNextQueuedJob().ifPresent(job -> {
            job.markRunning();
            jobRepository.save(job);

            try {
                jobExecutor.execute(job);
                job.markSucceeded();
            } catch (Exception e) {
                job.onFailureAndScheduleRetry(e.getMessage());
            }

            jobRepository.save(job);
        });
    }
}
