package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JobClaimConcurrencyTest extends AbstractPostgresTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void twoWorkersNeverClaimSameJob() throws InterruptedException {
        int jobCount = 20;
        for (int i = 0; i < jobCount; i++) {
            Job job = new Job(
                    UUID.randomUUID(),
                    JobStatus.QUEUED,
                    "SLEEP_JOB",
                    "{\"sleepSeconds\": 0}",
                    1,
                    null
            );
            jobRepository.saveAndFlush(job);
        }

        JobClaimService worker1 = new JobClaimService(jobRepository, "worker-1");
        JobClaimService worker2 = new JobClaimService(jobRepository, "worker-2");

        Set<UUID> claimedIds = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Runnable claimLoop = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            while (true) {
                UUID id = transactionTemplate.execute(status -> {
                    var opt = worker1.claimNextJob();
                    if (opt.isEmpty()) return null;
                    Job job = opt.get();
                    jobRepository.saveAndFlush(job);
                    return job.getJobId();
                });
                if (id == null) break;
                claimedIds.add(id);
            }
        };

        executor.submit(claimLoop);
        executor.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            while (true) {
                UUID id = transactionTemplate.execute(status -> {
                    var opt = worker2.claimNextJob();
                    if (opt.isEmpty()) return null;
                    Job job = opt.get();
                    jobRepository.saveAndFlush(job);
                    return job.getJobId();
                });
                if (id == null) break;
                claimedIds.add(id);
            }
        });

        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(claimedIds).hasSize(jobCount);
    }
}