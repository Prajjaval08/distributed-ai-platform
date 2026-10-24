Distributed AI Platform

A containerized distributed AI inference platform implementing asynchronous job submission, Kafka-based messaging, PostgreSQL persistence, Redis caching, object storage (MinIO), observability (OpenTelemetry, Jaeger, Prometheus, Grafana), and a small set of verified services (API + Worker).

Status: living engineering document — always aligned to repository contents.

## Project Status

Status as of 24 Oct 2026, updated with the Phase 22–25 work on branch
`phase-22-docker` (see [PR #1](https://github.com/Prajjaval08/distributed-ai-platform/pull/1)
to `master`). Phases 0–21 are done. Phases 22, 23 and 25 are prepared and
pushed (with one exception, below); Phase 24 is a plan only.

### 1. What the project is

An asynchronous AI inference platform. A client submits a job through a REST
API and gets `202 Accepted` with a job ID. The job is stored in PostgreSQL
and published to Kafka. A separate worker service consumes it, calls an AI
provider (a mock by default), uploads the result to S3-compatible storage
(MinIO) and marks the job COMPLETED.

**Stack:** Java 21, Spring Boot 3.3.4, Maven, PostgreSQL 16 (primary +
streaming replica), Flyway, Redis, Kafka (KRaft), MinIO, Nginx, Resilience4j,
Prometheus, Grafana, OpenTelemetry, Jaeger, JUnit 5, Mockito, Testcontainers,
k6, Docker Compose.

**Layout:** `services/api-service`, `services/worker-service`,
`infrastructure/docker` (docker-compose.yml), `infrastructure/nginx`,
`monitoring/prometheus`, `tests/k6`, `docs/`.

### 2. Completed phases

| Phase | Topic | Outcome |
|---|---|---|
| 0 | Environment setup | JDK 21, Maven, Docker (WSL2), Git, repo skeleton |
| 1 | Spring Boot skeleton | api-service starts, `/actuator/health` returns UP |
| 2 | PostgreSQL + Flyway + entities | Tables users, jobs, job_events, idempotency_keys with indexes; `ddl-auto: validate` |
| 3 | Auth + JWT | Register/login, BCrypt, stateless JWT filter, centralized error handler |
| 4 | Job APIs | Create/get/list/cancel/result, ownership via `findByIdAndUserId`, `parameters` JSONB migration (V6) |
| 5 | AI provider + object storage abstraction | `AiProvider` and `ObjectStorage` interfaces, Mock and OpenAI providers, MinIO via presigned URLs (path-style fix) |
| 6 | Kafka producer | Topics created with explicit partitions, job moves to QUEUED after publish |
| 7 | Worker service | Separate Maven module, job-state guard, manual offset commit, temporary `/process` endpoint removed |
| 8 | Retry + DLQ | Failure classification, exponential backoff with jitter, `ai-jobs-dlq` |
| 9 | Redis cache | Cache-aside for job status, user-scoped cache keys |
| 10 | Rate limiting | Redis fixed window: 100 jobs/h per user, 10 auth requests/h per IP, 429 with Retry-After |
| 11 | Idempotency | `Idempotency-Key` header, composite key (key + userId) |
| 12 | Priority jobs | Topics `ai-jobs-high`, `ai-jobs` and `ai-jobs-low`, strict-precedence `PriorityJobPoller` |
| 13 | Timeout + circuit breaker | Resilience4j circuit breakers and time limiters for the AI provider and storage |
| 14 | Nginx + multiple API instances | Round-robin load balancing; the shared Redis rate limit showed the API is stateless |
| 15 | DB read scaling | Real Postgres streaming replica, `AbstractRoutingDataSource`, only `listJobs` routed to the replica |
| 16 | Prometheus + Grafana | Both services scraped (UP); Grafana connected to Prometheus |
| 17 | Structured logging | JSON logs with `service`, `jobId` and `errorCode` via MDC |
| 18 | OpenTelemetry | Java agent, Jaeger, manual Kafka context propagation, real `traceId` in error responses |
| 19 | Automated tests | 38 tests (31 worker, 7 API) all passing; found and fixed the exception-unwrapping bug in `FailureClassifier` |
| 20 | k6 load testing | See section 3 below |
| 21 | Chaos testing | 7 scenarios exercised; see section 4 below |
| 22 | Docker production-like environment | DONE and pushed on `phase-22-docker`: multi-stage Dockerfiles for api-service and worker-service, both added to Compose with health checks, one `docker compose up` stack, env vars and secrets via `.env`, OTel agent (2.31.1, from the committed root jar) in the images |
| 23 | GitHub Actions CI/CD | PREPARED, NOT YET ON THE BRANCH: workflow builds and tests on every PR, Testcontainers in CI, Docker images tagged with the commit SHA, pushed to GHCR, pipeline fails when tests fail — but `.github/workflows/ci.yml` could not be pushed by the Muse GitHub App (403, missing workflow permission). Add it via the GitHub web UI on `phase-22-docker` or grant workflow permission and retry. See the Phase 23 section below. |
| 24 | Cloud deployment | PLAN ONLY in `docs/cloud-deployment.md`: provider approach, managed Postgres, Kafka and object storage where possible, secrets management, public demo URL. Nothing provisioned, no accounts opened, no money spent. |
| 25 | Kubernetes + HPA | DONE and pushed on `phase-22-docker`: Deployments, Services, ConfigMaps, placeholder-only Secrets, Ingress, HorizontalPodAutoscaler scaling workers on CPU (2–10 replicas, 70%). Kafka-lag scaling is documented in `infrastructure/kubernetes/worker-hpa.yaml`, not wired (it needs a lag exporter + prometheus-adapter). Manifests deploy only the two stateless services and assume PostgreSQL, Redis, Kafka, MinIO and Jaeger already exist as Services. |

### 3. Measured results (Phase 20)

- **Submission, 20 VUs:** about 25-28 req/s, P95 125-536 ms across runs, 0% errors.
- **Single worker:** 844/844 jobs completed. Average queue wait was 31.7 s and processing 0.062 s, so queue wait is about 99.8% of end-to-end time.
- **Two workers:** 887 jobs. Average queue wait fell to 24.0 s, a 24% reduction. Kafka showed 3 partitions per consumer.
- These are single runs, not a statistically controlled benchmark.

### 4. Chaos testing findings (Phase 21)

- **Confirmed gap:** there is no replica failover. When the replica is down, `GET /jobs` returns 500. An early apparent success came from a stale pooled connection.
- **Confirmed gap (code fix applied in this pass, see open items):** the rate limiter did not fail open when Redis was down, contrary to the Phase 10 design. Root cause found: Lettuce throws a `RedisConnectionException` instead of returning null, so the old null check never ran. `RateLimiter.checkAndIncrement` now catches `RuntimeException` and returns allowed.
- **Worked as designed:** Kafka rebalance and consumer restart (lag returned to 0), and MinIO outage (RETRY, RETRY, FAILED, DLQ).
- **Partial:** the API-instance kill test ran with a single backend (502), so it needs a clean re-run. The exact single-message mid-processing redelivery was not isolated.
- **Referenced from earlier phases:** AI timeout behavior was verified in Phases 8 and 13.
- Full report: `docs/failure-testing.md`.

### 5. Known open items

1. Redis fail-open bug in `RateLimiter` (`services/api-service/src/main/java/com/distributedai/api/ratelimit/RateLimiter.java`): FIXED in this pass — the method now catches `RuntimeException` (including Lettuce connection failures) and returns allowed. Not yet verified with a live Redis-down run; that still needs a Docker-host check.
2. Replica failover (`services/api-service/src/main/java/com/distributedai/api/config/RoutingDataSource.java`, `JobService.listJobs`): STILL OPEN, deliberately left unchanged in this pass. Why: the failure happens at connection/query time, after the routing decision, inside a `@Transactional(readOnly = true)` method already bound to the replica connection. A lookup-key-only change cannot retry on primary, and a real fix needs connection-level fallback plus transaction handling (new transaction for the retry) plus stale-pool eviction and a health-check strategy — none of which can be verified without a live replica-down run (no Docker in this environment). Guessing at it would risk breaking `listJobs` or masking the failure.
3. Re-run the API-instance kill test with two backends up.
4. Phase 16 leftovers: confirm the business metrics appear in Prometheus and provision a Grafana dashboard JSON.
5. Strict priority polling can starve LOW jobs; add fairness or aging (`services/worker-service/src/main/java/com/distributedai/worker/kafka/PriorityJobPoller.java`).
6. Workers process records sequentially. Bounded parallelism with safe offset commits would cut queue wait further.
7. Idempotency key cleanup job for expired keys is not built.
8. Transactional outbox is not built (the DB-write plus Kafka-publish dual-write gap).
9. `JobNotProcessableException` and its handler are dead code: DONE — removed, verified with no references remaining.
10. `OpenAiImageProvider` is not wired into worker-service; only the mock is.
11. The `FORCE_FAIL` test hook is still in `MockAiProvider`: DONE — removed, verified with no test depending on it.
12. Windows sleep or suspend caused stalled Kafka consumers during testing; this is an environment issue, not a bug.
13. Final README, commit to GitHub, and make sure no secrets are committed: README updated in this pass; commit is [PR #1](https://github.com/Prajjaval08/distributed-ai-platform/pull/1) (`phase-22-docker` → `master`), except the CI workflow file (see Phase 23 above). No real secrets were added; secret scan of this pass found only placeholders and pre-existing local defaults.

### 6. Recurring lessons from this build

- YAML indentation mistakes (a block nested under the wrong key) happened in Phases 3, 8, 10 and 15.
- PowerShell `Set-Content -Encoding UTF8` adds a BOM that breaks `javac`; write files with `[System.IO.File]::WriteAllText` and a no-BOM encoding.
- Files were sometimes created in the wrong folder or never created, so check with `dir` and `type` after each file step.
- Postgres `28P01` password errors recurred after container restarts; fix with `ALTER USER ... WITH PASSWORD`.

### 7. Resume bullet (measured numbers only)

> Built an asynchronous AI inference platform (Java, Spring Boot, Kafka, Redis, PostgreSQL, Docker) with retry/backoff/DLQ, circuit breakers, idempotency, priority queues, read-replica routing, JSON logging, Prometheus metrics and OpenTelemetry tracing across Kafka. Load-tested at 20 VUs (about 25 req/s, 0% errors); found that queue wait was over 99% of end-to-end latency with one worker and reduced it 24% (31.7 s to 24.0 s) by adding a second consumer. Chaos testing exposed two gaps: no replica failover and a rate limiter that did not fail open.

### 8. Remaining manual steps (not hidden)

- Add `.github/workflows/ci.yml` to branch `phase-22-docker` (GitHub web UI, or grant the Muse GitHub App workflow permission and retry the push), then merge [PR #1](https://github.com/Prajjaval08/distributed-ai-platform/pull/1) into `master`.
- On a Docker host: `cp .env.example infrastructure/docker/.env`, then `docker compose -f infrastructure/docker/docker-compose.yml up --build`, check `http://localhost:8080/actuator/health` and `http://localhost:8081/actuator/health`, and run a job-submission smoke test (register → login → submit job → 202 → poll to COMPLETED → fetch result). This also verifies the RateLimiter Redis-down fix and the still-open replica-failover gap.
- Cloud provisioning per `docs/cloud-deployment.md`, including the managed-Kafka cost decision.

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
## Phase 22–25 status (October 2025 pass)

Architecture is unchanged from the sections above: client → Nginx →
API → Kafka → workers → object storage, with PostgreSQL as the system of
record, Redis as a short-lived cache, and Prometheus/Grafana/Jaeger for
observability. The API docs summary is the "Verified API Endpoints"
table above (register, login, submit job → 202, get job, cancel job,
get result, get current user).

### One-command local setup (Phase 22)

```bash
# From the repository root. Defaults in docker-compose.yml cover local
# dev, so the stack starts even without a .env file; copy one anyway to
# set your own database/JWT secrets for any shared use.
cp .env.example infrastructure/docker/.env
docker compose -f infrastructure/docker/docker-compose.yml up --build
```

Then: API on http://localhost:8080 (also via Nginx on port 80), worker
health on http://localhost:8081/actuator/health, Jaeger on
http://localhost:16686, Grafana on http://localhost:3000.
Note: the header comment in `docker-compose.yml` says to copy
`.env.example` to the repository root; Compose actually loads `.env`
from the compose file's directory (`infrastructure/docker/`), so the
command above is the form that is picked up automatically. The
repository-root copy is harmless but is not what Compose reads.

Services are built from multi-stage Dockerfiles
(`services/api-service/Dockerfile`, `services/worker-service/Dockerfile`:
Maven + JDK 21 build, slim JRE 21 runtime, non-root user) with the
committed OpenTelemetry agent jar (2.31.1) attached via
`JAVA_TOOL_OPTIONS`. Health checks hit `/actuator/health`.

### Phase 23 — CI/CD

`.github/workflows/ci.yml` (prepared locally; NOT yet on branch
`phase-22-docker` because the Muse GitHub App gets a 403 for workflow
files) builds and tests both services on every pull
request and push to `master` (Java 21, Maven; the Testcontainers
PostgreSQL integration test runs on GitHub-hosted runners, which provide
Docker). Any test failure fails the pipeline. On pushes to `master`
only, tested images are pushed to GHCR tagged with the full commit SHA:
`ghcr.io/prajjaval08/distributed-ai-platform-api-service:<sha>` and
`...-worker-service:<sha>`. Add the workflow file to the branch first;
CI cannot run until then.

### Phase 25 — Kubernetes

Manifests live in `infrastructure/kubernetes/` (Deployments, Services,
ConfigMaps, placeholder-only Secrets, Ingress, worker HPA, plus a
kustomization). See `infrastructure/kubernetes/README.md` first: the
manifests deploy only the two stateless services and assume PostgreSQL,
Redis, Kafka, MinIO, and Jaeger already exist as Services. Workers scale
on CPU (2–10 replicas); Kafka-lag scaling is documented in
`worker-hpa.yaml` but not wired (it needs a lag exporter +
prometheus-adapter). These manifests have been YAML-checked but not
applied to a cluster.

### Phase 24 — Cloud deployment

Not provisioned. The concrete plan is in `docs/cloud-deployment.md`
(provider approach, managed Postgres/Kafka/object storage, secrets, env
vars, and the smoke-test steps for a public demo URL). No resources,
accounts, or spending were created in this pass.

### Remaining blockers (not hidden)

- **Pushed, except the CI workflow:** branch `phase-22-docker` is pushed
  and [PR #1](https://github.com/Prajjaval08/distributed-ai-platform/pull/1)
  to `master` is open. The only file still missing from the branch is
  `.github/workflows/ci.yml` (403, missing workflow permission for the
  Muse GitHub App). Add it, then merge the PR.
- **Docker run not verified here:** this environment has no Docker and
  Maven Central downloads failed through its proxy, so no image build,
  compose run, or Maven compile was proven in this pass. First run on a
  Docker host must be the one-command setup above, then the health
  endpoints and a job-submission smoke test — including a Redis-down
  check for the RateLimiter fix in this pass.
- **Known runtime gaps:** replica failover is still open (see the
  Project Status section above for why it was left unchanged),
  plus priority fairness, worker parallelism, transactional outbox,
  idempotency cleanup, and wiring the real OpenAI provider. They are
  listed with file names in that section rather than guessed at here.
