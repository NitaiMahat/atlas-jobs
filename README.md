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
- [Testing](#testing)
- [Observability & Debugging](#observability--debugging)
- [Technologies Used](#technologies-used)
- [Running Locally](#running-locally)
- [Running Multiple Workers](#running-multiple-workers)
- [Running Tests](#running-tests)

---

## Current Status

✅ Job submission via REST API  
✅ Persistent job storage using PostgreSQL  
✅ Database schema management with Flyway  
✅ Asynchronous job processing using workers  
✅ **Multiple worker instances running concurrently**  
✅ **Safe job claiming using row-level locking**  
✅ Retry logic with exponential backoff **and jitter** to prevent retry storms  
✅ Dead-letter handling for permanently failed jobs  
✅ Idempotent job creation to prevent duplicates  
✅ Worker ownership tracking (`worker_id`)  
✅ Debug endpoints for worker observability  
✅ Metrics endpoint with status counts, worker breakdown, attempt distribution, and recent activity window  
✅ **Integration tests using Testcontainers (real PostgreSQL)**  
✅ Dockerized local development setup

---

## What the System Does

- Accepts background jobs through an HTTP API
- Stores jobs reliably in the database
- Executes jobs asynchronously outside the request lifecycle
- Retries failed jobs automatically with increasing delays and jitter
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
   - `next_run_at` is scheduled in the future using exponential backoff with jitter
3. If `attempt_count == max_attempts`:
   - The job is marked `DEAD_LETTERED`

### Backoff Formula

```
delay = base × 3^(attempt - 1)    (capped at 300s)
jitter = delay × random(0.7 – 1.0)
```

Each retry delay is randomized within 70–100% of the nominal value. This prevents thundering herd — if 500 jobs fail at the same time, they won't all retry at the exact same moment.

### Backoff Schedule (Example)

| Attempt | Nominal Delay | With Jitter (range)   |
|---------|---------------|-----------------------|
| Retry 1 | 5 seconds     | ~3.5 – 5 seconds      |
| Retry 2 | 15 seconds    | ~10.5 – 15 seconds    |
| Retry 3 | 45 seconds    | ~31.5 – 45 seconds    |
| Retry 4 | 135 seconds   | ~94.5 – 135 seconds   |
| Retry 5 | 300 seconds   | ~210 – 300 seconds    |

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

| Column            | Description                     |
|-------------------|---------------------------------|
| `job_id`          | Unique job identifier (UUID)    |
| `status`          | Current job state               |
| `job_type`        | Type of job to execute          |
| `payload`         | Job-specific data               |
| `attempt_count`   | Number of attempts              |
| `max_attempts`    | Maximum retries                 |
| `idempotency_key` | Deduplication key               |
| `last_error`      | Last failure message            |
| `next_run_at`     | When job is eligible to run     |
| `worker_id`       | Worker that claimed the job     |
| `created_at`      | Creation timestamp              |
| `updated_at`      | Last update timestamp           |

### Flyway Migrations

| Version | Description                           |
|---------|---------------------------------------|
| V1      | Create jobs table with indexes        |
| V2      | Change payload from JSONB to TEXT     |
| V3      | Add `next_run_at` for retry scheduling|
| V4      | Add `last_error` column               |
| V5      | Add `worker_id` for ownership tracking|

---

## Testing

Atlas Jobs includes integration tests that run against a real PostgreSQL database using **Testcontainers**. No local Postgres installation is required — only Docker.

### Test Suite

| Test                          | What It Proves                                                                 |
|-------------------------------|--------------------------------------------------------------------------------|
| `JobServiceTest`              | Same idempotency key returns the same job; only one row exists in the database |
| `JobRetryAndDeadLetterTest`   | After one failure: status is QUEUED and `next_run_at` is in the future        |
| `JobRetryAndDeadLetterTest`   | After max attempts: status is DEAD_LETTERED                                    |
| `JobClaimConcurrencyTest`     | Two workers claiming 20 jobs concurrently never claim the same job             |
| `AtlasJobsApplicationTests`   | Spring application context loads successfully                                  |

### Test Infrastructure

- **Testcontainers** spins up a disposable Postgres 16 container per test class
- **Flyway** runs all migrations automatically against the test database
- `@DirtiesContext` ensures each test class gets a fresh Spring context and database
- Scheduling is disabled in tests (`spring.task.scheduling.enabled: false`) to prevent the worker from interfering

---

## Observability & Debugging

### Endpoints

| Endpoint                    | Method | Description                                           |
|-----------------------------|--------|-------------------------------------------------------|
| `POST /jobs`                | POST   | Submit a new job (with optional `Idempotency-Key` header) |
| `GET /jobs/{id}`            | GET    | Get job status by ID                                  |
| `GET /debug/workers`        | GET    | Job counts by worker and status                       |
| `GET /debug/workers/summary`| GET    | Job counts by status (all workers)                    |
| `GET /metrics`              | GET    | Full metrics: status counts, worker breakdown, attempt distribution, recent activity |

### Metrics Response

The `/metrics` endpoint returns:

- **`statusCounts`** — all-time job counts by status
- **`byWorker`** — counts broken down by worker and status
- **`attemptDistribution`** — how many jobs have 0, 1, 2… attempts
- **`scheduledForRetry`** — jobs queued but not yet eligible (future `next_run_at`)
- **`recent`** — status and worker counts for jobs updated in the last N minutes (default 5)

---

## Technologies Used

- **Java 21**
- **Spring Boot 4**
- **Spring Data JPA**
- **PostgreSQL 16**
- **Flyway** (schema migrations)
- **Docker & Docker Compose**
- **HikariCP**
- **Testcontainers** (integration testing)

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

## Running Tests

Requires Docker to be running (Testcontainers starts Postgres automatically).

```bash
./mvnw test
```

---

## License

This project is open source and available under the [MIT License](LICENSE).

---

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---


