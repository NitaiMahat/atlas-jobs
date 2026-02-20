package com.nitai.atlas_jobs.job;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class JobMetricsTest {

    @Test
    void metricsTrackCountsFailuresAndAverageDuration() {
        JobMetrics metrics = new JobMetrics();


        assertThat(metrics.getProcessedLastMinute()).isEqualTo(0);


        metrics.recordSuccess("SLEEP_JOB", 2_000_000_000L);
        metrics.recordSuccess("SLEEP_JOB", 4_000_000_000L);
        metrics.recordFailure("FAIL_JOB", 1_000_000_000L);


        assertThat(metrics.getProcessedLastMinute()).isEqualTo(3);


        Map<String, Long> failures = metrics.getFailuresByJobType();
        assertThat(failures).containsEntry("FAIL_JOB", 1L);


        Map<String, Double> avg = metrics.getAvgDurationSecondsByJobType();
        assertThat(avg.get("SLEEP_JOB")).isCloseTo(3.0, within(0.0001));
    }
}