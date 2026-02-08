package com.nitai.atlas_jobs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.util.UUID;

@Configuration
public class WorkerConfig {

    @Bean
    public String workerId(@Value("${atlas.worker-id:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
