package com.nitai.atlas_jobs.job;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    Optional<Job> findByIdempotencyKey(String idempotencyKey);
}
