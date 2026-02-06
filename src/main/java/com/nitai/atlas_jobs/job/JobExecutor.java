package com.nitai.atlas_jobs.job;
import org.springframework.stereotype.Component;
@Component
public class JobExecutor {
    public void execute(Job job) throws Exception {
        switch (job.getJobType()) {
            case "SLEEP_JOB" -> runSleep(job);
            case "FAIL_JOB" -> { throw new RuntimeException("Intentional failure for testing"); }
            default -> throw new IllegalArgumentException("Unknown job type: " + job.getJobType());
        }
    }

    private void runSleep(Job job) throws InterruptedException {
        int seconds = parseSleepSeconds(job.getPayload());
        Thread.sleep(seconds * 1000L);
    }

    private int parseSleepSeconds(String payload) {
        if (payload == null || payload.isBlank()) return 1;

        String cleaned = payload.replaceAll("\\s", "");
        int idx = cleaned.indexOf("sleepSeconds");
        if (idx == -1) return 1;

        int colon = cleaned.indexOf(":", idx);
        int end = cleaned.indexOf("}", colon);
        if (colon == -1 || end == -1) return 1;

        return Integer.parseInt(
                cleaned.substring(colon + 1, end).replaceAll("[^0-9]", "")
        );
    }
}
