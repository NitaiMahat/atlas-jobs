package com.nitai.atlas_jobs.job;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class WorkerShutdownLatch {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @PreDestroy
    public void signalShutdown() {
        shuttingDown.set(true);
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }
}