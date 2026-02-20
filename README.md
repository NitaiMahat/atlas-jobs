# Atlas Jobs

A distributed background job processing platform built with **Spring Boot** and **PostgreSQL**. Clients submit jobs over HTTP, jobs are stored in the database, and one or more workers poll and execute them asynchronously. Failed jobs are retried with exponential backoff and jitter, and permanently failing jobs are dead-lettered.

---

## Table of Contents

- [What This Project Does](#what-this-project-does)
- [Why a Job Queue Is Useful](#why-a-job-queue-is-useful)
- [How It Works (End-to-End)](#how-it-works-end-to-end)
- [Core Features](#core-features)
- [API Endpoints](#api-endpoints)
- [Job Types and Payloads](#job-types-and-payloads)
- [Retry Logic and Backoff](#retry-logic-and-backoff)
- [Dead Letters and Requeue](#dead-letters-and-requeue)
- [Stale Job Recovery](#stale-job-recovery)
- [Idempotency](#idempotency)
- [Observability and Metrics](#observability-and-metrics)
- [Security and Rate Limiting](#security-and-rate-limiting)
- [Database Schema and Migrations](#database-schema-and-migrations)
- [Configuration](#configuration)
- [Running Locally](#running-locally)
- [Running Multiple Workers](#running-multiple-workers)
- [Running Tests](#running-tests)
- [CI Pipeline](#ci-pipeline)
- [Tech Stack](#tech-stack)
- [License](#license)

---

## What This Project Does

Atlas Jobs provides a complete background job processing pipeline:

- Accepts job submissions via HTTP (`POST /jobs`)
- Persists jobs in PostgreSQL
- Executes jobs asynchronously outside the request lifecycle
- Retries failed jobs with exponential backoff and jitter
- Dead-letters jobs that exceed retry limits
- Supports multiple workers running concurrently with safe database locking
- Tracks worker ownership for observability
- Exposes debug and metrics endpoints for visibility
- Enforces optional basic auth and rate limiting
- Includes full integration testing with Testcontainers

---

## Why a Job Queue Is Useful

Running work **inside** an HTTP request is risky — the API stays open and can time out, failures are hard to retry safely, long-running tasks slow down user responses, and scaling becomes difficult.

A background job system solves this: the API responds quickly, work executes asynchronously and safely, failures can be retried with controlled timing, and you can scale workers horizontally without coordination services.

---

## How It Works (End-to-End)

1. **Client submits a job** via `POST /jobs`
2. **Job is stored** in the `jobs` table with status `QUEUED`
3. **Worker polls** every 2 seconds for the next eligible job
4. **Worker safely claims the job** using `FOR UPDATE SKIP LOCKED`
5. **Worker executes the job** based on `job_type`
6. **Success** → status becomes `SUCCEEDED`
7. **Failure** → attempt count increments, job is rescheduled with backoff
8. **Max attempts reached** → job becomes `DEAD_LETTERED`

---

## Core Features

### Safe Multi-Worker Processing

Workers use a single SQL query with row-level locking:

```sql
SELECT *
FROM jobs
WHERE status = 'QUEUED'
  AND next_run_at <= now()
ORDER BY created_at
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

This ensures no two workers ever process the same job, workers can scale horizontally without Redis or ZooKeeper, and the database remains the single source of truth.

### Asynchronous Worker Execution

`JobWorker` polls every 2 seconds. Each poll claims at most one job. Execution time is measured for metrics, and shutdown is graceful — the worker stops polling when the app shuts down.

### Payload Validation

Payload is stored as a raw JSON string and parsed/validated by job type:

- `SLEEP_JOB` — `sleepSeconds` must be 1–300
- `FAIL_JOB` — optional failure message

Invalid payloads return a `400` response.

### Rate Limiting (In-Memory)

Per-client IP, 60-second window. `POST /jobs` is limited by `atlas.rate-limit.jobs-per-minute`. Requeue endpoints are limited by `atlas.rate-limit.requeue-per-minute`. Uses `X-Forwarded-For` when present.

### Basic Auth (Optional)

When `atlas.security.enabled=true`, HTTP Basic is required for `/debug/**`, `/metrics`, and `/actuator/**`. Credentials are configured via `atlas.security.user` / `atlas.security.password`.

---

## API Endpoints

### Create Job

```
POST /jobs
```

**Request body:**

```json
{
  "jobType": "SLEEP_JOB",
  "payload": "{\"sleepSeconds\": 5}",
  "maxAttempts": 3
}
```

**Optional header:**

```
Idempotency-Key: your-key-here
```

**Response (201):**

```json
{
  "jobId": "uuid",
  "status": "QUEUED",
  "jobType": "SLEEP_JOB",
  "attemptCount": 0,
  "maxAttempts": 3,
  "createdAt": "2026-02-20T00:00:00Z",
  "updatedAt": "2026-02-20T00:00:00Z"
}
```

### Get Job

```
GET /jobs/{jobId}
```

### Requeue Dead-Lettered Job

```
POST /jobs/{jobId}/requeue
```

Returns `400` if the job is not dead-lettered.

### Bulk Requeue Dead Letters

```
POST /dead-letter/retry?limit=100
```

`limit` is clamped between 1 and 1000. Returns `{ "count": <number requeued> }`.

### Debug Endpoints

```
GET /debug/workers
GET /debug/workers/summary?sinceMinutes=<n>
```

### Metrics

```
GET /metrics?sinceMinutes=5
```

Returns `statusCounts`, `byWorker`, `attemptDistribution`, `scheduledForRetry`, recent window counts, `processedLastMinute`, `failuresByJobType`, and `avgDurationSecondsByJobType`.

---

## Job Types and Payloads

### `SLEEP_JOB`

Pauses the worker for N seconds.

```json
{ "sleepSeconds": 5 }
```

### `FAIL_JOB`

Always fails — used to test retries and dead-letter behavior.

```json
{ "message": "fail for testing" }
```

To add new job types, add parsing and validation in `PayloadParser`, add execution logic in `JobExecutor`, and update this README with examples.

---

## Retry Logic and Backoff

On failure, `attempt_count` increments. If attempts are below max, the job requeues with a delayed `next_run_at`. If attempts reach max, the job becomes `DEAD_LETTERED`.

**Backoff formula:**

```
delay = base * 3^(attempt - 1), capped at 300s
jitter = delay * random(0.7..1.0)
```

Jitter prevents thundering herd retries when many jobs fail at once.

---

## Dead Letters and Requeue

Jobs that exceed `max_attempts` become `DEAD_LETTERED`. You can requeue one job by ID or retry a batch. Requeue resets `attempt_count` to 0, clears `last_error`, clears `worker_id` and `started_at`, and sets `next_run_at` to now.

---

## Stale Job Recovery

If a job is stuck in `RUNNING` too long, it is treated as failed and rescheduled using the standard retry rules.

| Property | Default |
|---|---|
| `atlas.jobs.run-timeout-minutes` | `15` |
| `atlas.jobs.stale-recovery-interval-ms` | `60000` (60s) |

---

## Idempotency

If an `Idempotency-Key` header is provided, any existing job with that key is returned without creating a new row. A unique index ensures deduplication. This protects against network retries, client double-submits, and race conditions.

---

## Observability and Metrics

**Debug endpoints** — `/debug/workers` shows per-worker job counts by status; `/debug/workers/summary` shows totals by status.

**Metrics endpoint** — `/metrics` includes total counts by status, counts by worker and status, attempt distribution, scheduled retries (queued but not yet eligible), and recent activity over the last N minutes.

**In-memory stats** track processed jobs per last minute, failures by job type, and average duration by job type.

---

## Security and Rate Limiting

### Basic Auth

Enabled by default for `/debug/**`, `/metrics`, and `/actuator/**`.

| Variable | Default |
|---|---|
| `ATLAS_SECURITY_USER` | `admin` |
| `ATLAS_SECURITY_PASSWORD` | `admin123` |

To disable: `atlas.security.enabled=false`

### Rate Limiting

Enabled by default for `POST` requests to `/jobs`, `/jobs/{id}/requeue`, and `/dead-letter/retry`.

| Setting | Default |
|---|---|
| Job submits | 60 / minute |
| Requeues | 30 / minute |

To disable: `atlas.rate-limit.enabled=false`

---

## Database Schema and Migrations

### `jobs` Table

| Column | Description |
|---|---|
| `job_id` | UUID primary key |
| `status` | `QUEUED`, `RUNNING`, `SUCCEEDED`, `DEAD_LETTERED` |
| `job_type` | String job type |
| `payload` | Raw JSON string |
| `attempt_count` | Number of attempts |
| `max_attempts` | Max retries |
| `idempotency_key` | Optional dedupe key |
| `last_error` | Last failure message |
| `next_run_at` | When job can run next |
| `worker_id` | Worker that claimed the job |
| `started_at` | When job started running |
| `created_at` | Creation timestamp |
| `updated_at` | Last update timestamp |

### Flyway Migrations

- **V1** — Create `jobs` table and indexes
- **V2** — Change payload from `JSONB` to `TEXT`
- **V3** — Add `next_run_at`
- **V4** — Add `last_error`
- **V5** — Add `worker_id`
- **V6** — Add `started_at` and index for stale recovery

---

## Configuration

```yaml
spring.datasource.url=jdbc:postgresql://localhost:5432/atlas_jobs
spring.datasource.username=atlas
spring.datasource.password=atlas

atlas.rate-limit.enabled=true
atlas.rate-limit.jobs-per-minute=60
atlas.rate-limit.requeue-per-minute=30

atlas.security.enabled=true
atlas.security.user=admin
atlas.security.password=admin123

atlas.worker-id=<optional>
atlas.jobs.run-timeout-minutes=15
atlas.jobs.stale-recovery-interval-ms=60000
```

> In tests: scheduling, security, and rate limiting are all disabled.

---

## Running Locally

Start Postgres:

```bash
docker compose up -d
```

Run the app:

```bash
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

Workers safely share jobs using row-level locks.

---

## Running Tests

Requires Docker — Testcontainers starts Postgres automatically.

```bash
./mvnw test
```

---

## CI Pipeline

GitHub Actions runs:

1. Checkout
2. JDK 17 setup
3. Flyway migrate and validate (against a service Postgres)
4. `mvn verify`

---

## Tech Stack

| Technology | Version |
|---|---|
| Java | 17 |
| Spring Boot | 4.0.2 |
| Spring Data JPA | — |
| Spring Security | — |
| Flyway | — |
| PostgreSQL | 16 |
| Testcontainers | — |
| Docker & Docker Compose | — |
| Maven Wrapper | — |

---


