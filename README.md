# Atlas Jobs

A distributed background job processing platform built with Spring Boot and PostgreSQL.

Atlas Jobs allows clients to submit jobs via an HTTP API, processes them asynchronously using one or more workers, retries failed jobs with exponential backoff, and safely dead-letters jobs that permanently fail.

This project is designed to model **real-world backend job systems** used in production environments.

---

## Table of Contents

- [Current Status](#current-status)
- [What the System Does](#what-the-system-does)
- [Why a Job Queue?](#why-a-job-queue)
- [System Architecture](#system-architecture)
- [Job Lifecycle](#job-lifecycle)
- [Safe Multi-Worker Processing](#safe-multi-worker-processing)
- [Retry Logic & Exponential Backoff](#retry-logic--exponential-backoff)
- [Idempotency](#idempotency)
- [Database Schema](#database-schema)
- [Observability & Debugging](#observability--debugging)
- [Technologies Used](#technologies-used)
- [Running Locally](#running-locally)
- [Running Multiple Workers](#running-multiple-workers)

---

## Current Status

- Job submission via REST API  
- Persistent job storage using PostgreSQL  
- Database schema management with Flyway  
- Asynchronous job processing using workers  
- **Multiple worker instances running concurrently**  
- **Safe job claiming using row-level locking**  
- Retry logic with exponential backoff  
- Dead-letter handling for permanently failed jobs  
- Idempotent job creation to prevent duplicates  
- Worker ownership tracking (`worker_id`)  
- Debug endpoints for worker observability  
- Dockerized local development setup  

---

## What the System Does

- Accepts background jobs through an HTTP API  
- Stores jobs reliably in the database  
- Executes jobs asynchronously outside the request lifecycle  
- Retries failed jobs automatically with increasing delays  
- Prevents duplicate job execution using idempotency keys  
- Safely distributes jobs across multiple workers  
- Records which worker processed each job for observability  

---

## Why a Job Queue?

Instead of executing work directly inside an HTTP request:

- The API responds quickly  
- Long-running or unreliable work runs in the background  
- Failures can be retried safely  
- The system scales horizontally by adding workers  

This pattern is commonly used for:
- Email sending  
- Payment processing  
- File processing  
- External API calls  
- Event-driven workflows  

---

## System Architecture

```
Client
  |
  | POST /jobs
  v
Spring Boot API
  |
  | Persist job
  v
PostgreSQL (jobs table)
  |
  | Poll + lock (FOR UPDATE SKIP LOCKED)
  v
Worker 1 / Worker 2 / Worker N
  |
  | Execute job
  v
Update job status
```

---

## Job Lifecycle

Each job progresses through the following states:

- `QUEUED → RUNNING → SUCCEEDED`  
- `QUEUED → RUNNING → FAILED → RETRIED`  
- `FAILED → DEAD_LETTERED` (after max retries)  

---

## Safe Multi-Worker Processing

Atlas Jobs supports **multiple worker instances running at the same time**.

Workers safely claim jobs using PostgreSQL row-level locking:

```sql
SELECT *
FROM jobs
WHERE status = 'QUEUED'
  AND next_run_at <= now()
ORDER BY created_at
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

This guarantees:

- No two workers ever process the same job
- Workers can scale horizontally
- No coordination service (Redis/Zookeeper) is required
- The database is the source of truth

Each job records the `worker_id` that claimed it.

---

## Retry Logic & Exponential Backoff

When a job fails:

1. `attempt_count` is incremented
2. If `attempt_count < max_attempts`:
   - The job is re-queued
   - `next_run_at` is scheduled in the future
3. If `attempt_count == max_attempts`:
   - The job is marked `DEAD_LETTERED`

### Backoff Schedule (Example)

| Attempt | Delay        |
|---------|--------------|
| Retry 1 | ~5 seconds   |
| Retry 2 | ~15 seconds  |
| Retry 3 | ~45 seconds  |

This prevents retry storms and protects downstream services.

---

## Idempotency

Jobs can include an optional `idempotency_key`.

If the same job request is submitted more than once with the same key:

- The existing job is returned
- No duplicate job is created

This protects against:

- Network retries
- Client double-submits
- Accidental duplication

---

## Database Schema

### `jobs` Table

| Column            | Description                      |
|-------------------|----------------------------------|
| `job_id`          | Unique job identifier (UUID)     |
| `status`          | Current job state                |
| `job_type`        | Type of job to execute           |
| `payload`         | Job-specific data                |
| `attempt_count`   | Number of attempts               |
| `max_attempts`    | Maximum retries                  |
| `idempotency_key` | Deduplication key                |
| `last_error`      | Last failure message             |
| `next_run_at`     | When job is eligible to run      |
| `worker_id`       | Worker that claimed the job      |
| `created_at`      | Creation timestamp               |
| `updated_at`      | Last update timestamp            |

---

## Observability & Debugging

- Worker ownership is stored per job (`worker_id`)
- Debug endpoints expose worker/job distribution
- Jobs can be inspected directly via PostgreSQL
- Designed to integrate easily with metrics systems

---

## Technologies Used

- Java 21
- Spring Boot
- Spring Data JPA
- PostgreSQL 16
- Flyway (schema migrations)
- Docker & Docker Compose
- HikariCP

---

## Running Locally

```bash
docker compose up -d
./mvnw spring-boot:run
```

---

## Running Multiple Workers

```bash
# Terminal 1
export ATLAS_WORKER_ID=worker-8080
export SERVER_PORT=8080
./mvnw spring-boot:run

# Terminal 2
export ATLAS_WORKER_ID=worker-8081
export SERVER_PORT=8081
./mvnw spring-boot:run
```

Jobs will be distributed safely across workers.

---

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
