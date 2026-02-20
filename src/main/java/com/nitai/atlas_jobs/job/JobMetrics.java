package com.nitai.atlas_jobs.job;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

@Component
public class JobMetrics {

    private static final int WINDOW_SECONDS = 60;

    private final AtomicLongArray processedCounts = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray processedEpochSeconds = new AtomicLongArray(WINDOW_SECONDS);
    private final Object[] bucketLocks = new Object[WINDOW_SECONDS];

    private final ConcurrentHashMap<String, LongAdder> failuresByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> totalDurationNanosByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> durationCountByType = new ConcurrentHashMap<>();

    public JobMetrics() {
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            bucketLocks[i] = new Object();
        }
    }

    public void recordSuccess(String jobType, long durationNanos) {
        record(jobType, durationNanos, true);
    }

    public void recordFailure(String jobType, long durationNanos) {
        record(jobType, durationNanos, false);
    }

    private void record(String jobType, long durationNanos, boolean success) {
        String type = normalize(jobType);
        recordProcessed();
        recordDuration(type, durationNanos);
        if (!success) {
            failuresByType.computeIfAbsent(type, k -> new LongAdder()).increment();
        }
    }

    private void recordProcessed() {
        long nowSec = Instant.now().getEpochSecond();
        int idx = (int) (nowSec % WINDOW_SECONDS);
        synchronized (bucketLocks[idx]) {
            long bucketSec = processedEpochSeconds.get(idx);
            if (bucketSec != nowSec) {
                processedEpochSeconds.set(idx, nowSec);
                processedCounts.set(idx, 0L);
            }
            processedCounts.incrementAndGet(idx);
        }
    }

    private void recordDuration(String type, long durationNanos) {
        long safeNanos = Math.max(0L, durationNanos);
        totalDurationNanosByType.computeIfAbsent(type, k -> new LongAdder()).add(safeNanos);
        durationCountByType.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    public long getProcessedLastMinute() {
        long nowSec = Instant.now().getEpochSecond();
        long total = 0L;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            long bucketSec = processedEpochSeconds.get(i);
            if (nowSec - bucketSec < WINDOW_SECONDS) {
                total += processedCounts.get(i);
            }
        }
        return total;
    }

    public Map<String, Long> getFailuresByJobType() {
        Map<String, Long> result = new HashMap<>();
        failuresByType.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    public Map<String, Double> getAvgDurationSecondsByJobType() {
        Map<String, Double> result = new HashMap<>();
        totalDurationNanosByType.forEach((type, totalNanos) -> {
            LongAdder countAdder = durationCountByType.get(type);
            if (countAdder == null) return;
            long count = countAdder.sum();
            if (count == 0) return;
            double avgSeconds = totalNanos.sum() / 1_000_000_000.0 / count;
            result.put(type, avgSeconds);
        });
        return result;
    }

    private String normalize(String jobType) {
        if (jobType == null || jobType.isBlank()) return "unknown";
        return jobType;
    }
}