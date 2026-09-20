Distributed AI Platform

A containerized distributed AI inference platform implementing asynchronous job submission, Kafka-based messaging, PostgreSQL persistence, Redis caching, object storage (MinIO), observability (OpenTelemetry, Jaeger, Prometheus, Grafana), and a small set of verified services (API + Worker).

Status: living engineering document — always aligned to repository contents.

✨ Highlights

Capability

Status

Async job submission (HTTP -> Kafka)

🟢

Priority queues (high/normal/low)

🟢

PostgreSQL (Flyway migrations)

🟢

Redis caching (job read cache)

🟢

Kafka broker + topic init

🟢

Object storage (MinIO)

🟢

OpenTelemetry (traces)

🟢

Jaeger tracing (docker-compose)

🟢

Prometheus + Grafana

🟢

Nginx (local reverse proxy)

🟢

k6 load test scripts

🟢

Note: statuses are derived from files and configurations present in the repository. Planned or partial work is explicitly marked.

Quick links

Code: services — api-service, worker-service

Infra: infrastructure/docker/docker-compose.yml

Monitoring: monitoring/prometheus and monitoring/grafana

Tests: tests/k6

Architecture

flowchart LR
	Client --> Nginx
	Nginx --> API
	API --> Kafka
	API --> PostgreSQL
	API --> Redis
	Kafka --> Worker1
	Kafka --> Worker2
	Worker1 --> PostgreSQL
	Worker2 --> PostgreSQL
	Worker1 --> ObjectStorage
	Worker2 --> ObjectStorage

	API --> OpenTelemetry
	Worker1 --> OpenTelemetry
	Worker2 --> OpenTelemetry
	OpenTelemetry --> Jaeger
	API --> Prometheus
	Worker1 --> Prometheus
	Worker2 --> Prometheus
	Prometheus --> Grafana

All components in the diagram are present in the repository. API and Worker services live under services/; infrastructure is under infrastructure/docker/ and infrastructure/nginx/; monitoring configs are under monitoring/. For local scaling experiments, two Worker JVMs can run simultaneously on ports 8081 and 8082 while sharing the same Kafka consumer groups.

Request Lifecycle (actual)

Client sends HTTP request to POST /api/v1/jobs handled by JobController in api-service.

JobService validates and persists a Job row in PostgreSQL (Flyway migrations under services/api-service/src/main/resources/db/migration).

The JobProducer publishes a JobMessage to the appropriate Kafka topic (ai-jobs-high, ai-jobs, ai-jobs-low) depending on priority.

worker-service runs a PriorityJobPoller which polls high → normal → low topics and invokes JobProcessingService.

Worker calls configured AI provider (mock by default), writes results to object storage (MinIO) and updates jobs row with result_url and status.

Clients poll GET /api/v1/jobs/{id} or GET /api/v1/jobs/{id}/result to retrieve status or result. Redis is used for short-lived job read caching by JobCacheService.

Where useful, the code references are:

services/api-service/src/main/java/com/distributedai/api/controller/JobController.java

services/api-service/src/main/java/com/distributedai/api/kafka/JobProducer.java

services/worker-service/src/main/java/com/distributedai/worker/kafka/PriorityJobPoller.java

DB migrations: services/api-service/src/main/resources/db/migration/

Core Components

Component

Technology

Responsibility

API Service

Spring Boot (Java 21)

REST API, authentication, job submission (services/api-service)

Worker Service

Spring Boot (Java 21)

Kafka consumer, job processing (services/worker-service)

Database

PostgreSQL

Persistent job & user state (Flyway migrations present)

Message Broker

Kafka (Confluent image)

Topics: ai-jobs-high, ai-jobs, ai-jobs-low, ai-job-results, ai-job-failures, ai-jobs-dlq

Cache

Redis

Short-lived job response cache

Gateway

Nginx

Local reverse proxy (infrastructure/nginx/nginx.conf)

Object Storage

MinIO

Artifact storage and presigned URLs

Tracing

OpenTelemetry + Jaeger

Trace propagation from API to Worker (agent jar present and Jaeger in compose)

Metrics

Prometheus + Micrometer

Metrics exposed by services; Prometheus + Grafana in compose

Load tests

k6

Job submission script located at tests/k6/job-submission-test.js

Repository Structure

distributed-ai-platform/
├── infrastructure/
│   ├── docker/
│   │   └── docker-compose.yml
│   ├── nginx/
│   │   └── nginx.conf
│   ├── kafka/
│   ├── postgres/
│   └── redis/
├── monitoring/
│   ├── grafana/
│   └── prometheus/
├── services/
│   ├── api-service/
│   └── worker-service/
├── tests/
│   └── k6/
└── README.md

Technology Stack (verified)

Backend

Java 21, Spring Boot 3.x

Messaging

Apache Kafka (Confluent broker image; topics created by kafka-init service)

Data

PostgreSQL (images and Flyway migrations present)

Redis

Storage

MinIO (S3-compatible used in docker-compose)

Infrastructure

Docker Compose, Nginx

Observability

OpenTelemetry (agent jar present), Jaeger, Prometheus, Grafana

Testing

JUnit tests (reports under services/*/target/surefire-reports)

k6 scripts in tests/k6

Verified API Endpoints

Primary API endpoints are implemented in api-service controllers.

Method

Endpoint

Purpose

POST

/api/v1/auth/register

Register new user (AuthController)

POST

/api/v1/auth/login

Login, returns JWT (AuthController)

POST

/api/v1/jobs

Submit job (JobController) — returns 202 Accepted on success

GET

/api/v1/jobs/{id}

Get job metadata/status (JobController)

POST

/api/v1/jobs/{id}/cancel

Cancel job (JobController)

GET

/api/v1/jobs/{id}/result

Get job result URL/status (JobController)

GET

/api/v1/users/me

Get current user info (UserController)

Example: submit job (JSON body) — CreateJobRequest fields are prompt, model, optional priority, optional parameters.

Kafka / Async Processing (actual)

Topics are defined/created in infrastructure/docker/docker-compose.yml via the kafka-init helper: ai-jobs-high, ai-jobs, ai-jobs-low, ai-job-results, ai-job-failures, ai-jobs-dlq.

API JobProducer selects topic by JobPriority and injects OpenTelemetry headers into Kafka record headers.

Worker uses a polling model (PriorityJobPoller) with three consumers and strict precedence (HIGH → NORMAL → LOW).

Consumer groups used: ai-worker-group-high, ai-worker-group, ai-worker-group-low (see PriorityJobPoller).

The normal-priority group has been verified with two local Worker instances: Kafka assigns partitions 0,1,2 to one consumer and 3,4,5 to the other, with observed lag 0 across all six partitions after processing.

The current poller processes the records returned by each poll() sequentially before committing offsets. Diagnostic logging reports poll batch sizes.

DLQ topic exists (ai-jobs-dlq) and DlqProducer is present in the worker codebase.

Data Model (implemented)

users table migration: V1__create_users_table.sql

jobs table migration: V2__create_jobs_table.sql — fields: id, user_id, prompt, model, status, priority, retry_count, result_url, error_code, error_message, timestamps.

idempotency_keys table migration: V4__create_idempotency_keys_table.sql (per-user primary key).

Refer to services/api-service/src/main/resources/db/migration/ for SQL DDLs.

Caching (Redis)

JobCacheService is used by JobController#getJob to try a Redis read before falling back to PostgreSQL and to populate Redis on misses.

Cache TTL is configurable via cache.job-ttl-seconds in service application.yml files (default 60s).

Observability (what's present)

OpenTelemetry: agent JAR included at repo root (opentelemetry-javaagent.jar) and OTel API dependencies are present in both services.

Jaeger is configured in infrastructure/docker/docker-compose.yml (collector + UI).

Prometheus and Grafana services are present in compose, and Micrometer Prometheus registry is a dependency in both services.

Tracing propagation is implemented: API injects traces into Kafka record headers and Worker extracts them.

Testing & Load Testing

Unit & integration tests: JUnit tests exist for API and Worker services; test reports are present under services/*/target/surefire-reports/.

k6: tests/k6/job-submission-test.js drives HTTP job-submission traffic through the local Nginx endpoint and expects a JWT via the TOKEN environment variable.

Current diagnostic scenario: ramping-vus, 0 → 20 VUs over 10s, 20 VUs for 20s, then ramp-down over 5s.

Current load-test path: k6 → Nginx:80 → API:8080 → Kafka → Worker JVMs.

Verified local scaling results

A two-worker local run has been verified with:

Worker #1 listening on 8081

Worker #2 listening on 8082

API listening on 8080

Nginx listening on 80

Kafka consumer-group inspection verified that the normal-priority consumer group distributes the six ai-jobs partitions across the two Worker instances:

One consumer: partitions 0,1,2

Second consumer: partitions 3,4,5

Observed lag: 0 on all six partitions after processing

A database measurement for jobs created between 2026-09-18 15:46:00 and 15:47:00 recorded:

Completed jobs: 887

Average queue wait: 24.045 s

Average processing time: 0.075 s

Average total time: 24.119 s

These measurements show that, in this local test, queue wait is substantially larger than measured job-processing time. The current PriorityJobPoller processes each returned Kafka record sequentially on the poller thread, so additional worker instances increase Kafka consumer capacity but do not by themselves make each Worker JVM process multiple jobs concurrently.

A separate k6 two-worker run recorded:

HTTP requests: 477

Throughput: 13.29 req/s

Average HTTP latency: 680.71 ms

Median HTTP latency: 360.06 ms

P95 HTTP latency: 2.20 s

HTTP failures: 0%

Custom job-submission errors: 0%

The k6 and database figures above are from separate measurements and should not be treated as a single run.

Current performance work

The next controlled optimization is to evaluate bounded parallel job processing inside each Worker instance. Kafka partition assignment and Nginx routing have already been verified, so Kafka partitioning is not currently being changed as part of this experiment. Any worker-concurrency change must preserve safe Kafka offset-commit behavior and the existing job idempotency/status semantics.

Getting Started (verified file-based instructions)

Prerequisites

Docker & Docker Compose installed

Java 21 + Maven if you want to run services locally outside containers

Quick local bring-up (PowerShell)

# From repository root
docker compose -f infrastructure/docker/docker-compose.yml up -d

# Check containers
docker ps --filter "name=ai-platform-"

Notes:

Compose brings up PostgreSQL, Redis, MinIO, Kafka, Jaeger, Prometheus, Grafana and Nginx.

The compose file creates Kafka topics via the kafka-init helper container.

API local dev (if running with the compose infra):

# Build and run api-service via Maven (optional)
cd services/api-service
mvn spring-boot:run
# service listens on port 8080 by default

Worker local dev (optional):

cd services/worker-service
mvn spring-boot:run
# worker listens on port 8081 by default

k6 example (requires a valid JWT in TOKEN env var):

$env:TOKEN = "YOUR_JWT"
k6 run tests/k6/job-submission-test.js

Configuration (important environment variables)

Variable

Purpose

Default / notes

DB_URL

Main PostgreSQL JDBC URL

jdbc:postgresql://localhost:55432/ai_platform (in application.yml)

REPLICA_DB_URL

Replica JDBC URL

jdbc:postgresql://localhost:5433/ai_platform

POSTGRES_PASSWORD

Postgres password (compose uses env)

devpassword if not set in .env

KAFKA_BOOTSTRAP_SERVERS

Kafka bootstrap server

localhost:9092

REDIS_HOST / REDIS_PORT

Redis connection

localhost:6379

OBJECT_STORAGE_ENDPOINT

MinIO endpoint

http://localhost:9000

JWT_SECRET

JWT signing secret for dev

local-dev-only-secret-please-change-me-32chars-min (do NOT use in production)

See services/*/src/main/resources/application.yml for service-specific defaults.

Reliability & Resilience (implemented / present)

Idempotency: idempotency_keys table and DuplicateIdempotencyKeyException handling in JobController are implemented.

Retry settings for worker are present in worker-service application.yml (retry section, max-attempts, delays, jitter).

Worker uses explicit Kafka commit (consumer.commitSync()) after processing records in PriorityJobPoller.

Engineering Decisions (documented in code)

Kafka chosen for asynchronous, priority-queued job distribution (see JobProducer + PriorityJobPoller).

Redis used as a short-lived read cache to reduce DB reads for frequent status polling.

Flyway used for migrations to keep schema versioned and reproducible.

Roadmap (repository-derived)

🟢 Core API and Worker — implemented

🟢 Kafka topics + init — implemented in compose

🟢 PostgreSQL + Flyway migrations — implemented

🟢 Redis cache — implemented

🟢 MinIO object storage — implemented

🟢 Observability stack (OTel agent, Jaeger, Prometheus, Grafana) — implemented

🟢 k6 diagnostic benchmarking — initial local measurements captured

🟡 Worker concurrency optimization — bounded parallel processing experiment pending

⚪ Kubernetes manifests for production deployment — infrastructure/kubernetes/ exists but treat as planned until validated

Changelog / Recent Evolution (sourced from repository)

Current snapshot

Added API and Worker services (Spring Boot)

Added Flyway migrations for users, jobs, idempotency_keys

Implemented Kafka producer/consumer with trace propagation

Added Redis cache for job reads

Added MinIO, Jaeger, Prometheus, Grafana to compose

Added k6 job submission script

Verified two local Worker instances can share Kafka partitions in the same consumer groups

Added diagnostic poll-batch logging to PriorityJobPoller

Captured initial two-worker queue-wait and processing-time measurements

Troubleshooting (quick tips)

PostgreSQL connection: compose maps main DB to 55432; ensure POSTGRES_PASSWORD matches application.yml or .env.

Kafka: topics are created by kafka-init; if topics missing, re-run the kafka-init logic or create manually against localhost:9092.

Redis: service name in compose is redis and default port 6379.

JWT: development secret in application.yml is for local/dev only — rotate for production.

If you'd like, I can now:

run a quick validation (linting of README links),

commit the README and update the todo list step statuses,

or run docker compose up locally and capture first-start logs (you must confirm).

File: services/api-service/src/main/resources/db/migration/V2__create_jobs_table.sql