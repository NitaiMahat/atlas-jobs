# Atlas Jobs

A distributed background job processing platform built with Spring Boot and PostgreSQL.

Atlas Jobs allows clients to submit jobs via an HTTP API, processes them asynchronously using a worker, retries failed jobs with exponential backoff, and safely dead-letters jobs that permanently fail.

This project is designed to model real-world backend job systems used in production.

## Current Status

- Job submission via REST API
- Persistent job storage using PostgreSQL
- Database schema management with Flyway
- Asynchronous job processing using a worker
- Safe job claiming with row-level locking
- Retry logic with exponential backoff
- Dead-letter handling for permanently failed jobs
- Idempotent job creation to prevent duplicates
- Dockerized local development setup

## What the System Does

- Accepts background jobs through an API
- Stores jobs reliably in the database
- Executes jobs asynchronously
- Retries failed jobs automatically with increasing delays
- Prevents duplicate job execution
- Exposes job status for observability

## Job Lifecycle

- QUEUED → RUNNING → SUCCEEDED
- QUEUED → RUNNING → FAILED → RETRIED
- FAILED → DEAD_LETTERED (after max retries)

## Technologies Used

- Java 21
- Spring Boot
- Spring Data JPA
- PostgreSQL 16
- Flyway
- Docker & Docker Compose
- HikariCP

## Running Locally

```bash
docker compose up -d
./mvnw spring-boot:run
