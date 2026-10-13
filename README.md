# Atlas Jobs

**A PostgreSQL-backed background job processing system built with Java and Spring Boot.**

Atlas Jobs accepts jobs through an HTTP API, stores them durably in PostgreSQL, and processes them asynchronously through independently running workers. It explores practical backend concerns: concurrent job claiming, retries, idempotent submissions, failed-job recovery, and visibility into worker activity.

The goal is to demonstrate **how to design and reason about a reliable job queue using a relational database**, including what the system can—and cannot—guarantee when workers or infrastructure fail.

**Stack:** Java 17 · Spring Boot 4.0.2 · PostgreSQL 16 · Spring Data JPA · Flyway · Docker Compose · Maven · JUnit/Testcontainers · GitHub Actions

## At a glance

| Capability | Implementation |
| --- | --- |
| Durable queue | PostgreSQL `jobs` table |
| Concurrent workers | Transactional row-level claims using `FOR UPDATE SKIP LOCKED` |
| Delayed retries | Exponential backoff with jitter |
| Terminal failures | `DEAD_LETTERED` status, individual/bulk requeue endpoints |
| Recovery | Periodic detection of jobs left in `RUNNING` beyond a configured timeout |
| Submission deduplication | Optional `Idempotency-Key` and database uniqueness constraint |
| Visibility | Job-status API, worker/debug views, and metrics endpoint |
| Validation | Job-type-specific payload checks |
| Development and CI | Docker Compose, Flyway, Testcontainers, GitHub Actions |

## Why I built it

Processing long-running work directly inside HTTP requests couples execution time to request latency and makes failure handling awkward. Atlas Jobs separates *accepting* a request from *executing* the work.

I chose PostgreSQL instead of introducing a message broker so I could investigate what a database-backed queue requires: atomic claiming, crash recovery, retry scheduling, idempotency, and the trade-offs of polling and database contention.

This is an engineering project, **not a claim that PostgreSQL queues universally replace dedicated message brokers**.

## Architecture

```text
                       POST /jobs
                           |
                           v
                    +-------------+
                    | Spring Boot |
                    |   Job API   |
                    +------+------+ 
                           |
                     persist job
                           |
                           v
                  +-------------------+
                  |    PostgreSQL     |
                  |  jobs + indexes   |
                  +---------+---------+
                            ^
                atomic claim / status updates
                ____________|____________
               |            |            |
          +----+----+  +----+----+  +----+----+
          | Worker 1|  | Worker 2|  | Worker N|
          +---------+  +---------+  +---------+
               |            |            |
               +------------+------------+
                            |
                  execute job payload
                            |
          +-----------------+-----------------+
          |                                   |
       SUCCESS                             FAILURE
          |                                   |
      SUCCEEDED                  retry later / DEAD_LETTERED
```

Each application instance can poll the shared PostgreSQL queue. PostgreSQL coordinates claims through row-level locks; no separate Redis or ZooKeeper coordination service is used in this implementation.

### Job lifecycle

```text
                 POST /jobs
                     |
                     v
                  QUEUED <-------------------------+
                     |                              |
                 claimed                            |
                     v                              |
                  RUNNING                           |
                 /     \                            |
           success     failed / stale               |
             |               |                       |
             v               +---- retry eligible --+
         SUCCEEDED           |
                             +---- attempts exhausted
                                             |
                                             v
                                       DEAD_LETTERED
                                             |
                                        manual requeue
                                             |
                                             v
                                           QUEUED
```

A delayed retry remains in `QUEUED` with a future `next_run_at` value; it is not eligible for a claim until its scheduled time.

## Key engineering decisions

### 1. Claim work with PostgreSQL row locks

The claim path uses `FOR UPDATE SKIP LOCKED`, selecting an eligible queued job while skipping rows already locked by another transaction.

A simplified representation of the selection query is:

```sql
SELECT *
FROM jobs
WHERE status = 'QUEUED'
  AND next_run_at <= now()
ORDER BY created_at
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

**What this provides:** coordination between concurrent claim transactions, so two transactions need not wait on or claim the same locked queued row.

**What it does not provide:** a global exactly-once execution guarantee. A worker can crash after doing work but before recording success, and a recovered job may execute again. The distinction between **claiming** and **executing** matters whenever the job has external side effects.

### 2. Retry failed jobs with backoff and jitter

A failed attempt increments the attempt count. Jobs with attempts remaining are scheduled for another execution; exhausted jobs are marked `DEAD_LETTERED`.

The documented retry calculation is:

```text
uncapped_delay = base_delay * 3^(attempt - 1)
delay          = min(uncapped_delay, 300 seconds)
retry_delay    = delay * random(0.7, 1.0)
```

Jitter spreads retries that otherwise might occur at the same time. Failed jobs can be inspected and requeued individually or in bulk.

### 3. Recover abandoned work

If a worker exits while a job remains `RUNNING`, a periodic recovery process identifies jobs that have exceeded a configurable execution timeout and routes them through retry handling.

Default settings documented by the project:

| Property | Default |
| --- | --- |
| `atlas.jobs.run-timeout-minutes` | `15` |
| `atlas.jobs.stale-recovery-interval-ms` | `60000` |

**Trade-off:** a timeout-based approach cannot always distinguish a crashed worker from a slow but healthy one. Without additional lease renewal/fencing safeguards, a still-running worker and a recovery attempt can overlap. Job handlers should therefore be designed with duplicate execution in mind.

### 4. Deduplicate client submissions

Clients may send an optional `Idempotency-Key` when submitting jobs. The key is persisted and constrained for uniqueness, so repeated requests using the same key can refer to an existing job instead of creating another row.

This is **submission deduplication**, not proof that an external side effect executes exactly once. Clients should not reuse an idempotency key for unrelated operations; production-grade APIs should also define how to handle a reused key with a different payload.

### 5. Keep failure states observable

Job records include their status, attempts, last error, scheduling timestamps, and worker information. The project exposes endpoints for inspecting jobs and summarizing recent activity. These facilities help distinguish accepted jobs from completed work and explain why an item was retried or dead-lettered.

## Run locally

### Prerequisites

- **Java 17**
- **Docker and Docker Compose** (for PostgreSQL and integration tests)
- No separate Maven installation required; the Maven wrapper is included

Clone the repository:

```bash
git clone https://github.com/NitaiMahat/atlas-jobs.git
cd atlas-jobs
```

Start PostgreSQL:

```bash
docker compose up -d
```

Start Spring Boot (macOS/Linux):

```bash
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

By default, the application uses the local PostgreSQL service configured for the repository. Docker Compose currently provisions database `atlas_jobs`, user `atlas`, and password `atlas` on port `5432` **for local development only**. Change these credentials before deployment.

Flyway manages the database schema during application startup. The API is served on port `8080` unless overridden.

### Submit and inspect a job

Create a short-running demo job:

```bash
curl -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-sleep-001' \
  -d '{
    "jobType": "SLEEP_JOB",
    "payload": "{\"sleepSeconds\": 5}",
    "maxAttempts": 3
  }'
```

The API returns a job ID and initial status. Use that ID to inspect progress:

```bash
curl http://localhost:8080/jobs/YOUR_JOB_ID
```

Submit the same request with the same `Idempotency-Key` to exercise submission deduplication.

To exercise retries and dead lettering, submit a deliberately failing job:

```bash
curl -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "jobType": "FAIL_JOB",
    "payload": "{\"message\": \"intentional demo failure\"}",
    "maxAttempts": 2
  }'
```

## Run multiple workers

Run the application twice on different ports. Both instances share the same database.

**Terminal 1:**

```bash
ATLAS_WORKER_ID=worker-8080 SERVER_PORT=8080 ./mvnw spring-boot:run
```

**Terminal 2:**

```bash
ATLAS_WORKER_ID=worker-8081 SERVER_PORT=8081 ./mvnw spring-boot:run
```

These inline environment-variable commands use POSIX shell syntax. On PowerShell, set the environment variables using `$env:ATLAS_WORKER_ID` and `$env:SERVER_PORT` before running `.\mvnw.cmd spring-boot:run`.

Each instance polls for eligible jobs. The documented worker polling interval is approximately two seconds; each poll claims at most one job.

## HTTP API

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/jobs` | Submit a job; optionally include `Idempotency-Key` |
| `GET` | `/jobs/{jobId}` | Read job state |
| `POST` | `/jobs/{jobId}/requeue` | Requeue a dead-lettered job |
| `POST` | `/dead-letter/retry?limit=100` | Bulk requeue dead-lettered jobs |
| `GET` | `/debug/workers` | Worker activity details |
| `GET` | `/debug/workers/summary?sinceMinutes=5` | Worker summary |
| `GET` | `/metrics?sinceMinutes=5` | Job and worker metrics |

The bulk requeue limit is bounded to the documented range of 1–1,000. Requeue operations reset retry-related job state for another attempt cycle.

### Supported demo jobs

| Type | Payload example | Behavior |
| --- | --- | --- |
| `SLEEP_JOB` | `{"sleepSeconds": 5}` | Sleeps for the requested duration; accepted range 1–300 seconds |
| `FAIL_JOB` | `{"message": "testing retries"}` | Intentionally fails to demonstrate retry/dead-letter handling |

The `payload` field of the submission API is a **JSON-encoded string**, not a nested JSON object. To add job types, extend the repository's `PayloadParser` and `JobExecutor` logic.

## Reliability experiments and measurements

The earlier project documentation reported the following development-run results. **These numbers have not been independently reproduced as part of this README update.** They should be treated as historical experiment reports until the workload, environment, scripts, and raw outputs are published together.

### HTTP submission load test

| Metric | Previously reported result |
| --- | --- |
| Jobs submitted | ~27,000 over 3 minutes |
| HTTP submission rate | 145+ requests/sec |
| Reported request errors | 0% |
| p50 HTTP latency | ~7 ms |
| p95 HTTP latency | ~11–15 ms |
| p99 HTTP latency | ~123–205 ms |

**Interpretation:** HTTP request throughput measures how fast the API accepts submissions, **not** how fast background jobs finish. The error percentage refers to the reported load-test requests; it should not be interpreted as a universal processing-success guarantee.

### Background job processing throughput

The earlier tests also reported processing throughput for `SLEEP_JOB`:

| Worker instances | Previously reported completed jobs/sec |
| --- | ---: |
| 1 | 0.67 |
| 2 | 0.98 |
| 4 | 1.64 |

The four-worker result is approximately 2.45 times the reported single-worker throughput **for that workload and test environment**. It is not a universal scaling factor, and the numbers do not establish linear scaling.

### Failure experiments

The previous README described manual experiments involving:

- Terminating a worker during execution
- Temporarily interrupting/restarting PostgreSQL
- Recovering stale `RUNNING` jobs
- Exercising retry and dead-letter behavior

Those are valuable scenarios to test. However, passing a handful of manual trials does not demonstrate that jobs can never be lost or duplicated under all possible failures.

### How to make the results reproducible

Before treating the benchmarks as verified portfolio evidence, include:

1. The actual k6 script and invocation command.
2. Worker count, database resources, machine configuration, Java settings, and duration.
3. The job payload mix, arrival rate, and whether the queue was drained before measurement.
4. HTTP response counts and error criteria **separate** from job completion counts.
5. Logs/raw results, the Git commit tested, and instructions to repeat each experiment.
6. Explicit assertions for crash recovery and repeated-submission behavior.

Until then, these measurements are useful historical observations, **not independently verifiable performance guarantees**.

## Testing and continuous integration

Run the test suite with Docker available:

```bash
./mvnw test
```

The project includes Testcontainers dependencies for PostgreSQL integration testing. GitHub Actions runs on pushes to `main` and pull requests, starts PostgreSQL 16, applies and validates Flyway migrations, and executes Maven `verify` on Java 17.

```bash
./mvnw verify
```

See [the CI workflow](.github/workflows/ci.yml) and the source under [`src/test`](src/test).

**Suggested additional reliability coverage:** simultaneous claims against a real database, duplicate idempotency keys under concurrent submissions, crashes at each state transition, invalid payload rejection, retry exhaustion, and a long-running job that exceeds the stale-recovery threshold. These are test targets, **not claims that every case is currently covered**.

## Database schema and migrations

Flyway migrations live in [`src/main/resources/db/migration`](src/main/resources/db/migration). The queue's key fields include:

| Field | Purpose |
| --- | --- |
| `job_id` | Primary identifier |
| `job_type`, `payload` | Execution type and input |
| `status` | `QUEUED`, `RUNNING`, `SUCCEEDED`, or `DEAD_LETTERED` |
| `attempt_count`, `max_attempts` | Retry bookkeeping |
| `idempotency_key` | Optional submission deduplication |
| `next_run_at` | Eligibility for scheduled execution |
| `last_error` | Latest recorded failure |
| `worker_id`, `started_at` | Claim/recovery metadata |
| `created_at`, `updated_at` | Audit timestamps |

The current README history describes migrations from the initial jobs table through retry scheduling, error metadata, worker ownership, and stale-recovery timestamps. The migration SQL files are the authoritative schema reference.

## Security and operational limitations

This is a locally runnable engineering demonstration, **not a hardened production job service**.

- **Demo credentials:** the documented defaults include `admin` / `admin123` for protected observability endpoints. Replace them and configure secure secret management before external deployment.
- **Rate limiting:** the current limiter is in memory and keyed by client IP. It is not a globally coordinated limit across independently running application instances.
- **Forwarded headers:** trusting `X-Forwarded-For` without a configured trusted reverse proxy can make IP-based controls unreliable.
- **Timeout-based recovery:** a stale job can be eligible for retry while its original handler is still active. There is no general exactly-once side-effect guarantee.
- **Idempotency scope:** uniqueness of submission keys avoids duplicate job records for the same key; it does not automatically deduplicate external effects.
- **Polling architecture:** polling frequency, locking, database write load, and worker count can limit throughput as workloads grow.
- **Job handlers:** the included `SLEEP_JOB` and `FAIL_JOB` are intentionally simple demonstration workloads; they do not establish real-world business-handler throughput.

Exposed API endpoints, credentials, authorization, logging of sensitive payloads, and resource limits need a separate deployment review before real customer use.

## Where to go next

Planned areas worth investigating (not yet claimed as implemented):

- Lease renewal and fencing tokens to reduce stale-worker races
- Automated failure-injection tests at claim/execute/acknowledge boundaries
- Reproducible benchmark artifacts with completed-job throughput
- Better security boundaries for public API and administrative operations
- Metrics for queue age, end-to-end completion latency, retries, and terminal failures

## What this project demonstrates

Atlas Jobs is an exercise in **backend engineering judgment**, not just connecting an API to a database. The core questions are how to coordinate competing workers, recover abandoned records, retry with bounded behavior, observe failures, and describe guarantees precisely.

The design intentionally keeps the stack small enough to study those concerns directly. Its most important lesson is that *preventing duplicate concurrent claims is different from preventing duplicate execution or duplicate external side effects*.

---

**Author:** [Nitai Mahat](https://github.com/NitaiMahat)  
**Repository:** https://github.com/NitaiMahat/atlas-jobs
