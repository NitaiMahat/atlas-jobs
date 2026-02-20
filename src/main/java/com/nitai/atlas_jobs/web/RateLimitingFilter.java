package com.nitai.atlas_jobs.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "atlas.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;

    private final long jobsPerMinute;
    private final long requeuePerMinute;

    private final ConcurrentHashMap<String, Counter> jobsCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> requeueCounters = new ConcurrentHashMap<>();

    public RateLimitingFilter(
            @Value("${atlas.rate-limit.jobs-per-minute:60}") long jobsPerMinute,
            @Value("${atlas.rate-limit.requeue-per-minute:30}") long requeuePerMinute
    ) {
        this.jobsPerMinute = jobsPerMinute;
        this.requeuePerMinute = requeuePerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String key = resolveClientKey(request);

        if ("/jobs".equals(path)) {
            if (isRateLimited(jobsCounters, key, jobsPerMinute)) {
                reject(response, "Rate limit exceeded for POST /jobs");
                return;
            }
        } else if (path.endsWith("/requeue") || "/dead-letter/retry".equals(path)) {
            if (isRateLimited(requeueCounters, key, requeuePerMinute)) {
                reject(response, "Rate limit exceeded for requeue");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isRateLimited(ConcurrentHashMap<String, Counter> counters, String key, long limit) {
        long now = System.currentTimeMillis();
        Counter bucket = counters.computeIfAbsent(key, k -> new Counter(now));
        synchronized (bucket) {
            if (now - bucket.windowStartMs >= WINDOW_MS) {
                bucket.windowStartMs = now;
                bucket.count = 0;
            }
            bucket.count++;
            return bucket.count > limit;
        }
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(429);
        response.setContentType("text/plain");
        response.getWriter().write(message);
    }

    private static class Counter {
        long windowStartMs;
        long count;

        Counter(long windowStartMs) {
            this.windowStartMs = windowStartMs;
            this.count = 0;
        }
    }
}