# Atlas Jobs

**A PostgreSQL-backed background job processing system built with Java and Spring Boot.**

Atlas Jobs accepts jobs through an HTTP API, stores them durably in PostgreSQL, and processes them asynchronously through independently running workers. It explores practical backend concerns: concurrent job claiming, retries, idempotent submissions, failed-job recovery, and visibility into worker activity.

The built-in [queue console](http://localhost:8080/) includes guided live demos and a job inspector. Its `SLEEP_JOB` and `FAIL_JOB` handlers simulate work and failure; report generation is an example use, not an implemented feature.

The goal is to demonstrate **how to design and reason about a reliable job queue using a relational database**, including what the system can—and cannot—guarantee when workers or infrastructure fail.

**Stack:** Java 17 · Spring Boot 4.0.2 · PostgreSQL 16 · Spring Data JPA · Flyway · Docker Compose · Maven · JUnit/Testcontainers · GitHub Actions

## At a glance

| Capability | Implementation |
| --- | --- |
| Durable queue | PostgreSQL `jobs` table |
| Concurrent workers | Transactional row-level claims using `FOR UPDATE SKIP LOCKED` |
| Delayed retries | Exponential backoff with jitter |
| Terminal failures | `DEAD_LETTERED` status, individual/bulk requeue endpoints |
| Recovery | Expiring leases, fenced completion, and retry of abandoned jobs |
| Submission deduplication | Optional `Idempotency-Key` and database uniqueness constraint |
| Visibility | Queue console, job-status API, worker/debug views, and metrics endpoint |
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

Each claim increments `lease_generation` and sets `lease_expires_at`. Completion and failure updates require the current worker ID, generation, and an unexpired lease. Recovery locks expired rows, then schedules a retry or dead-letters the job. The next claim receives a new generation, so an old worker cannot overwrite the new owner's job state. `JobLeaseFencingTest` reproduces this race against PostgreSQL.

Default settings documented by the project:

| Property | Default |
| --- | --- |
| `atlas.jobs.run-timeout-minutes` | `15` |
| `atlas.jobs.stale-recovery-interval-ms` | `60000` |

**Trade-off:** a fixed timeout cannot always distinguish a crashed worker from a slow but healthy one. Two executions can overlap. Fencing protects the `jobs` row, not external effects such as emails or files; those need their own idempotency or fencing. This is at-least-once execution, not exactly-once execution. Jobs exceeding the lease lose the right to complete; longer workloads need lease renewal or a longer timeout.

During an upgrade, stop workers running the old code before applying V7 and starting new workers. Old binaries do not enforce the fence. V7 gives already-running jobs the default 15-minute deadline; installations with a custom timeout should review those rows during migration.

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
Open `http://localhost:8080/` and sign in with `ATLAS_SECURITY_USER` and `ATLAS_SECURITY_PASSWORD` (local defaults: `admin` / `admin123`). The console and API use HTTP Basic authentication. Set different credentials before sharing a running instance.

### Submit and inspect a job

Create a short-running demo job:

```bash
curl -u admin:admin123 -X POST http://localhost:8080/jobs \
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
curl -u admin:admin123 http://localhost:8080/jobs/YOUR_JOB_ID
```

Submit the same request with the same `Idempotency-Key` to exercise submission deduplication.

To exercise retries and dead lettering, submit a deliberately failing job:

```bash
curl -u admin:admin123 -X POST http://localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "jobType": "FAIL_JOB",
    "payload": "{\"message\": \"intentional demo failure\"}",
    "maxAttempts": 2
  }'
```

## Guided demos

Open the console at `http://localhost:8080/` with an otherwise quiet queue. Its live buttons submit real jobs through `POST /jobs`; the displayed counters are not simulated. The inspector refreshes every three seconds, so a fast intermediate state may be missed.

### 1. Claim to completion

Click **Run success demo**. A one-second `SLEEP_JOB` moves from `QUEUED` to `RUNNING` to `SUCCEEDED`. Inspect its worker, ownership generation, and lease deadline. A fast job may appear already succeeded by the first refresh.

- **What I built:** HTTP submission, durable storage, worker claiming, and conditional completion.
- **What I learned:** accepting a request and finishing background work are separate events; clients need a status lookup.
- **Explain it:** “The API stores the job. A worker claims it, receives a lease generation, and completes it only while that claim still owns it.”

### 2. Retry to dead letter

Click **Run retry demo**. A `FAIL_JOB` allows two attempts. After the first failure, inspect its last error and future **Next eligible** time. After the second, it becomes `DEAD_LETTERED`. The exact retry delay varies because of jitter. **Requeue this job** demonstrates the manual path; its intentionally failing payload will fail again.

- **What I built:** delayed retries with backoff and jitter, attempt limits, dead-letter handling, and requeue.
- **What I learned:** transient failures need controlled retries; repeated failures need a visible terminal state.
- **Explain it:** “A failed attempt schedules `next_run_at`. Workers claim only eligible jobs. At the limit, the job is dead-lettered.”

### 3. Stale worker rejected

With Docker running, run the deterministic PostgreSQL integration test from the repository root:

```powershell
.\mvnw.cmd -Dtest=JobLeaseFencingTest test
```

On macOS/Linux, use `./mvnw -Dtest=JobLeaseFencingTest test`. The test expires a claim, runs recovery, claims the job again, and verifies that the old generation cannot complete or fail the new claim. Expect **2 tests, 0 failures**.

- **What I built:** lease generations, expiry, recovery, and ownership checks on job updates.
- **What I learned:** a timeout alone does not stop a resumed worker; the row needs a fence. External side effects need separate protection.
- **Explain it:** “The old worker's generation 1 update affects zero rows after generation 2 takes ownership. The new owner can complete successfully.”

The test uses an isolated application instance and database, so it does not increment the console's **Stale writes blocked** counter. The live retry demo does not prove stale-worker fencing; this test does.

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
| `GET` | `/jobs?limit=30` | List recent jobs (limit capped at 100) |
| `GET` | `/jobs/{jobId}` | Read job state |
| `POST` | `/jobs/{jobId}/requeue` | Requeue a dead-lettered job |
| `POST` | `/dead-letter/retry?limit=100` | Bulk requeue dead-lettered jobs |
| `GET` | `/debug/workers` | Worker activity details |
| `GET` | `/debug/workers/summary?sinceMinutes=5` | Worker summary |
| `GET` | `/metrics?sinceMinutes=5` | Job and worker metrics |

The metrics response includes `oldestReadyJobWaitSeconds`, `expiredLeases`, and `staleCompletionRejections`. The last two are counters for the current application instance and reset on restart.

The bulk requeue limit is bounded to the documented range of 1–1,000. Requeue operations reset retry-related job state for another attempt cycle.

### Supported demo jobs

| Type | Payload example | Behavior |
| --- | --- | --- |
| `SLEEP_JOB` | `{"sleepSeconds": 5}` | Sleeps for the requested duration; accepted range 1–300 seconds |
| `FAIL_JOB` | `{"message": "testing retries"}` | Intentionally fails to demonstrate retry/dead-letter handling |

The `payload` field of the submission API is a **JSON-encoded string**, not a nested JSON object. To add job types, extend the repository's `PayloadParser` and `JobExecutor` logic.

## Reliability experiments and measurements

The earlier project documentation reported the following development-run results. **These numbers have not been reproduced for the lease and console upgrade.** They are historical observations, not current processing-throughput claims.

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

The repository now includes `load-tests/jobs.js` for HTTP submission testing and `load-tests/processing_benchmark.py` for completed-job throughput. The latter submits one-second jobs and polls until each is terminal. On an idle queue, run it once each with 1, 2, and 4 worker instances, using the same hardware and job count:

```bash
python load-tests/processing_benchmark.py --worker-count 1 --jobs 20
```

Record its JSON output for each run, including elapsed time, successful completions, worker count, operating system, and CPU count. The default API rate limit is 60 submissions per minute; adjust it for larger runs. Do not compare its completed jobs per second with the HTTP submission rate from k6.

Before treating benchmarks as verified portfolio evidence, publish:

1. The exact script invocation command and any rate-limit changes.
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

The PostgreSQL suite includes concurrent claims, retry exhaustion, dead-letter recovery, and `JobLeaseFencingTest`, which rejects stale completion and failure after a newer claim. Automatic worker polling is disabled in tests with `atlas.jobs.worker-enabled: false` so tests control claims explicitly. The local `verify` run on 2026-10-14 passed **15 tests, 0 failures**.

Further coverage could exercise crashes at additional state transitions and concurrent submissions sharing an idempotency key.

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
| `lease_generation`, `lease_expires_at` | Fenced ownership and lease deadline |
| `created_at`, `updated_at` | Audit timestamps |

V7 adds the lease fields and an index for expired running jobs. The migration SQL files are the authoritative schema reference.

## Security and operational limitations

This is a locally runnable engineering demonstration, **not a hardened production job service**.

- **Demo credentials:** the local defaults include `admin` / `admin123` for the console and API. Replace them and configure secure secret management before external deployment.
- **Rate limiting:** the current limiter is in memory and keyed by client IP. It is not a globally coordinated limit across independently running application instances.
- **Forwarded headers:** trusting `X-Forwarded-For` without a configured trusted reverse proxy can make IP-based controls unreliable.
- **Fixed leases:** a stale job can be retried while its original handler is still active. Its old generation cannot update the job row, but external side effects have no general exactly-once guarantee. Longer jobs need lease renewal or a longer timeout.
- **Idempotency scope:** uniqueness of submission keys avoids duplicate job records for the same key; it does not automatically deduplicate external effects.
- **Polling architecture:** polling frequency, locking, database write load, and worker count can limit throughput as workloads grow.
- **Job handlers:** the included `SLEEP_JOB` and `FAIL_JOB` are intentionally simple demonstration workloads; they do not establish real-world business-handler throughput.

Exposed API endpoints, credentials, authorization, logging of sensitive payloads, and resource limits need a separate deployment review before real customer use.

## Where to go next

Planned areas worth investigating (not yet claimed as implemented):

- Lease renewal for work that can exceed the fixed deadline
- More failure-injection tests at claim/execute/acknowledge boundaries
- Published benchmark artifacts with completed-job throughput
- Better security boundaries for public API and administrative operations
- End-to-end completion latency and richer retry and terminal-failure metrics

## What this project demonstrates

Atlas Jobs is an exercise in **backend engineering judgment**, not just connecting an API to a database. The core questions are how to coordinate competing workers, recover abandoned records, retry with bounded behavior, observe failures, and describe guarantees precisely.

The design intentionally keeps the stack small enough to study those concerns directly. Its most important lesson is that *preventing duplicate concurrent claims is different from preventing duplicate execution or duplicate external side effects*.

---

**Author:** [Nitai Mahat](https://github.com/NitaiMahat)  
**Repository:** https://github.com/NitaiMahat/atlas-jobs
