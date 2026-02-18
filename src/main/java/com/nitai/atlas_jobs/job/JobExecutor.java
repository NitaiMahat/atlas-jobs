package com.nitai.atlas_jobs.job;

import com.nitai.atlas_jobs.job.payload.FailJobPayload;
import com.nitai.atlas_jobs.job.payload.PayloadParser;
import com.nitai.atlas_jobs.job.payload.SleepJobPayload;
import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

    private final PayloadParser payloadParser;

    public JobExecutor(PayloadParser payloadParser) {
        this.payloadParser = payloadParser;
    }

    public void execute(Job job) throws Exception {
        switch (job.getJobType()) {
            case "SLEEP_JOB" -> runSleep(job);
            case "FAIL_JOB" -> runFail(job);
            default -> throw new IllegalArgumentException("Unknown job type: " + job.getJobType());
        }
    }

    private void runSleep(Job job) throws InterruptedException {
        SleepJobPayload payload = payloadParser.parseSleepPayload(job.getPayload());
        int seconds = payload.getSleepSeconds() == null ? 1 : payload.getSleepSeconds();
        Thread.sleep(seconds * 1000L);
    }

    private void runFail(Job job) {
        FailJobPayload payload = payloadParser.parseFailPayload(job.getPayload());
        String message = (payload.getMessage() == null || payload.getMessage().isBlank())
                ? "Intentional failure for testing"
                : payload.getMessage();
        throw new RuntimeException(message);
    }
}