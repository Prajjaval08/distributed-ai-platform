# Distributed AI Inference Platform

A production-oriented platform for asynchronous AI inference and image-generation workloads. Clients submit a job through a REST API and get an immediate `202 Accepted` with a job ID. A separate worker fleet does the actual inference, so slow AI calls never block the API.

The AI provider is treated as a pluggable, unreliable external dependency. The platform is built to stay reliable even when that dependency is not.

> **Status:** Phases 0-21 are complete. Remaining: Dockerizing the services (22), CI/CD (23), cloud deployment (24) and Kubernetes (25). See [Roadmap](#roadmap).

---

## Table of contents

1. [Architecture](#architecture)
2. [Features](#features)
3. [Tech stack](#tech-stack)
4. [Repository structure](#repository-structure)
5. [Prerequisites](#prerequisites)
6. [Quick start](#quick-start)
7. [Configuration](#configuration)
8. [API reference](#api-reference)
9. [Job lifecycle](#job-lifecycle)
10. [Design decisions and trade-offs](#design-decisions-and-trade-offs)
11. [Observability](#observability)
12. [Testing](#testing)
13. [Measured performance](#measured-performance)
14. [Failure and chaos testing](#failure-and-chaos-testing)
15. [Known limitations](#known-limitations)
16. [Roadmap](#roadmap)
17. [Troubleshooting](#troubleshooting)

---

## Architecture

```
Client
   |
   v
Nginx (load balancer, :80)
   |
   +---------------+---------------+
   |                               |
   v                               v
API Instance 1 (:8080)       API Instance 2 (:8081)
   |                               |
   +---------------+---------------+
                   |
     +-------------+--------------+
     |             |              |
     v             v              v
  Redis       PostgreSQL         Kafka
 (cache,      primary  ---WAL--> replica     |
  rate         (writes)         (list reads)  |
  limit)                                      v
                          +-------------------+-------------------+
                          |                                       |
                          v                                       v
                  Worker instance 1                      Worker instance N
                          |
                          v
                 AI Provider (mock / OpenAI)
                          |
                          v
              MinIO (S3-compatible object storage)
```

**Request flow**

1. `POST /api/v1/jobs` authenticates the user, validates the payload, checks the rate limit and the idempotency key.
2. The API writes the job row (`CREATED`), publishes a message to Kafka, then marks the job `QUEUED` and returns `202`.
3. A worker consumes the message, checks the job is still `QUEUED` (this makes redelivery safe), marks it `PROCESSING`, and calls the AI provider through a circuit breaker and timeout.
4. The result is uploaded to MinIO, the job becomes `COMPLETED`, and the cache is updated.
5. Failures are classified, retried with backoff and jitter, and sent to a dead-letter queue when they are permanent or retries run out.

---

## Features

**Core**
- Asynchronous job submission with durable job state in PostgreSQL
- JWT authentication, BCrypt password hashing, ownership checks on every job
- Kafka-backed worker fleet that scales independently of the API
- Presigned result URLs (results are never publicly readable)

**Reliability**
- Failure classification: `TRANSIENT`, `PERMANENT`, `DEPENDENCY`, `INTERNAL`
- Exponential backoff with jitter, bounded retries, dead-letter queue (`ai-jobs-dlq`)
- Resilience4j circuit breakers and time limiters on the AI provider and object storage
- Idempotency keys (`Idempotency-Key` header), enforced by a database composite key
- Manual Kafka offset commit after the terminal state is persisted

**Performance and scaling**
- Redis cache-aside for job status reads (user-scoped keys)
- Redis rate limiting: 100 job submissions per user per hour, 10 auth calls per IP per hour
- Priority queues: separate `HIGH`, `NORMAL` and `LOW` topics, polled in strict precedence
- Stateless API instances behind Nginx
- PostgreSQL streaming replica serving the job-history list endpoint

**Observability**
- Prometheus metrics and a Grafana data source
- Structured JSON logs with `service`, `jobId` and `errorCode` fields
- OpenTelemetry tracing into Jaeger, including trace propagation across Kafka

---

## Tech stack

| Layer | Technology |
|---|---|
| Language / framework | Java 21, Spring Boot 3.3.4, Maven |
| Database | PostgreSQL 16 (primary + streaming replica), Flyway migrations |
| Cache / rate limit | Redis 7 |
| Messaging | Apache Kafka (KRaft mode, no Zookeeper) |
| Object storage | MinIO (S3-compatible) via AWS SDK v2 |
| AI | `AiProvider` interface with a mock and an OpenAI implementation |
| Resilience | Resilience4j |
| Proxy | Nginx |
| Metrics | Micrometer, Prometheus, Grafana |
| Tracing | OpenTelemetry Java agent, Jaeger |
| Logging | Logback with logstash-logback-encoder |
| Testing | JUnit 5, Mockito, Testcontainers, MockMvc, k6 |
| Containers | Docker, Docker Compose |

---

## Repository structure

```
distributed-ai-platform/
├── services/
│   ├── api-service/          # REST API, auth, job management, Kafka producer
│   └── worker-service/       # Kafka consumer, AI call, retries, DLQ
├── infrastructure/
│   ├── docker/               # docker-compose.yml (infrastructure services)
│   ├── nginx/                # nginx.conf
│   ├── kafka/ postgres/ redis/ kubernetes/   # reserved for later phases
├── monitoring/
│   ├── prometheus/           # prometheus.yml
│   ├── grafana/ otel/
├── tests/k6/                 # load test scripts
├── docs/                     # failure-testing.md and design notes
├── opentelemetry-javaagent.jar
├── .env.example
└── README.md
```

---

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker Desktop (on Windows this uses WSL2)
- Git
- k6 (only for load tests)

Check your setup:

```bash
java -version      # must report 21
mvn -version       # must report Java 21
docker --version
docker compose version
```

---

## Quick start

The two services currently run as local JVM processes. Everything else runs in Docker. Containerizing the services is Phase 22.

### 1. Start the infrastructure

```bash
cd infrastructure/docker
docker compose up -d
docker compose ps
```

Wait until Postgres, Redis, Kafka and MinIO report `healthy`. The `kafka-init` and `minio-init` containers exit with code 0 after creating the topics and the bucket.

### 2. Start the API service

```bash
cd services/api-service
export JWT_SECRET="a-long-random-secret-at-least-32-characters"
mvn spring-boot:run
```

On PowerShell:

```powershell
$env:JWT_SECRET = "a-long-random-secret-at-least-32-characters"
mvn spring-boot:run
```

To run a second instance for load balancing, use a different port and the **same** `JWT_SECRET`:

```powershell
$env:SERVER_PORT = "8081"
mvn spring-boot:run
```

### 3. Start the worker service

```bash
cd services/worker-service
mvn spring-boot:run
```

Look for `PriorityJobPoller started` in the log. You can run more than one worker; Kafka distributes partitions across them.

### 4. Try it

```bash
# Register
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"password123"}'

# Submit a job (replace TOKEN with the accessToken from above)
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"A futuristic city at night","model":"image-model-v1","priority":"HIGH"}'

# Poll the job
curl http://localhost:8080/api/v1/jobs/JOB_ID -H "Authorization: Bearer TOKEN"
```

When the job shows `COMPLETED`, open the `resultUrl` in a browser. With the mock provider you get a placeholder PNG with your prompt drawn on it.

### Useful local URLs

| Service | URL |
|---|---|
| API (direct) | http://localhost:8080 |
| API (via Nginx) | http://localhost |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin; add Prometheus at `http://prometheus:9090`) |
| Jaeger | http://localhost:16686 |
| MinIO console | http://localhost:9001 (minioadmin/minioadmin) |

---

## Configuration

Copy `.env.example` to `.env` for local values. Never commit real secrets.

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | none (required for real use) | HMAC key for JWT signing, 32+ characters |
| `JWT_EXPIRATION_MS` | 900000 | Access token lifetime (15 minutes) |
| `POSTGRES_PASSWORD` | devpassword | Database password |
| `REPLICA_DB_URL` | jdbc:postgresql://localhost:5433/ai_platform | Read replica URL |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka |
| `REDIS_HOST` / `REDIS_PORT` | localhost / 6379 | Redis |
| `REDIS_JOB_CACHE_TTL_SECONDS` | 60 | Job status cache TTL |
| `RATE_LIMIT_JOBS_PER_HOUR` | 100 | Per-user job submission limit |
| `RATE_LIMIT_AUTH_PER_HOUR` | 10 | Per-IP auth limit |
| `AI_PROVIDER_TYPE` | mock | `mock` or `openai` |
| `AI_PROVIDER_API_KEY` | empty | Required when type is `openai` |
| `OBJECT_STORAGE_*` | MinIO local defaults | Endpoint, bucket, keys, region |
| `AI_PROVIDER_TIMEOUT_SECONDS` | 10 | Worker timeout for AI calls |
| `OBJECT_STORAGE_TIMEOUT_SECONDS` | 5 | Worker timeout for uploads |
| `CIRCUIT_BREAKER_*` | 50% / 10s / 5 calls | Failure threshold, open-state wait, minimum calls |
| `SERVER_PORT` | 8080 | API port (set 8081 for a second instance) |

The mock provider is the default so you can run everything without an OpenAI key or any cost. The mock treats the prompt `FORCE_FAIL` as a simulated dependency failure, which is useful for demonstrating retries, the circuit breaker and the DLQ.

---

## API reference

All endpoints except auth and health require `Authorization: Bearer <JWT>`.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/auth/register` | Create an account, returns a token |
| POST | `/api/v1/auth/login` | Log in, returns a token |
| POST | `/api/v1/jobs` | Submit a job (`202`). Optional `Idempotency-Key` header |
| GET | `/api/v1/jobs` | Paginated job history (`?page=0&size=20`), served from the replica |
| GET | `/api/v1/jobs/{id}` | Job status (cache-aside) |
| POST | `/api/v1/jobs/{id}/cancel` | Cancel a `CREATED` or `QUEUED` job |
| GET | `/api/v1/jobs/{id}/result` | Result URL; `409` until the job is `COMPLETED` |
| GET | `/api/v1/users/me` | Current user |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Metrics |

**Create job request**

```json
{
  "prompt": "A futuristic city at night",
  "model": "image-model-v1",
  "priority": "NORMAL",
  "parameters": { "width": 1024, "height": 1024 }
}
```

`priority` is `HIGH`, `NORMAL` or `LOW` (default `NORMAL`).

**Error envelope** (all non-2xx responses)

```json
{
  "errorCode": "RATE_LIMITED",
  "message": "Too many requests. Try again in 42 seconds.",
  "traceId": "1d2c903fecd978764de7251b084d6bf7",
  "timestamp": "2026-09-10T19:14:17Z"
}
```

Error codes include `INVALID_REQUEST`, `UNAUTHORIZED`, `EMAIL_ALREADY_EXISTS`, `JOB_NOT_FOUND`, `JOB_NOT_CANCELLABLE`, `RESULT_NOT_READY`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE` and `INTERNAL_ERROR`. Note that an unauthenticated call to a protected route currently returns Spring Security's default `403`, not the error envelope.

**Rate limits:** `POST /jobs` returns `429` with a `Retry-After` header after 100 submissions per user per hour. `register` and `login` share a limit of 10 per IP per hour.

---

## Job lifecycle

```
CREATED -> QUEUED -> PROCESSING -> COMPLETED
              |           |
              |           +-> (retry with backoff) -> FAILED -> DLQ
              +-> CANCELLED
```

- A job becomes `QUEUED` only after the Kafka publish succeeds.
- The worker acts only on `QUEUED` jobs. A redelivered message for a job already in another state is skipped, which makes at-least-once delivery safe.
- `FAILED` is terminal. The DLQ message is an operational artifact carrying the job ID, failure category, retry count and error message.
- Every state change is recorded in the append-only `job_events` table.

---

## Design decisions and trade-offs

| Decision | Why | Trade-off |
|---|---|---|
| Async jobs via Kafka | Inference is slow and bursty; the API must stay fast | Eventual completion; clients poll |
| Database commit before Kafka publish, whole request fails if publish fails | Fails in the safe direction (no phantom jobs) | A Kafka outage blocks submissions. A transactional outbox would remove the dual-write gap (not built) |
| Flyway owns the schema, Hibernate only validates | Versioned, reviewable, reproducible migrations | Migrations must be written by hand |
| Cache key scoped by user ID | A cache hit must not bypass the ownership check | Slightly lower sharing of cache entries |
| Fixed-window rate limiter | Simple, atomic with Redis `INCR` + `EXPIRE` | A burst at a window boundary can allow close to 2x the limit |
| Separate topic per priority, manual poll loop | Kafka has no per-message priority; separate topics give a structural guarantee | Strict precedence can starve `LOW` jobs; the loop is less idiomatic than `@KafkaListener` |
| Read replica only for the job list | Reads that need read-after-write consistency stay on the primary | No automatic failover to the primary (see limitations) |
| Duplicated entity and DTO classes per service | Keeps services independently buildable | Duplication until a shared module is justified |
| JSON Kafka messages without type headers | Producer and consumer can have different class names | No schema registry or compatibility enforcement |
| In-process retry with `Thread.sleep` | Keeps retry state on the job row, simple to explain | Blocks the consumer thread during backoff |

---

## Observability

- **Metrics:** both services expose `/actuator/prometheus`; Prometheus scrapes them every 10 seconds. Spring and JVM metrics (`http_server_requests`, HikariCP, JVM) come from Micrometer. The worker also records `ai_provider_latency`, `jobs_completed` and `jobs_failed`.
- **Logs:** one JSON object per line with `timestamp`, `level`, `logger`, `message`, `service`, and `jobId` / `errorCode` when applicable. Secrets (passwords, JWTs, API keys, Authorization headers) are never logged.
- **Tracing:** run each service with the OpenTelemetry agent to see a single trace from `POST /api/v1/jobs` through the Kafka publish to the worker's processing and database writes:

```powershell
$env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4317"
$env:OTEL_SERVICE_NAME = "api-service"
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-javaagent:C:\path\to\opentelemetry-javaagent.jar"
```

The worker's `PriorityJobPoller` extracts trace context from Kafka headers manually, because the agent does not instrument a hand-built `KafkaConsumer` poll loop.

---

## Testing

```bash
# Worker unit tests
cd services/worker-service && mvn test

# API unit, repository (Testcontainers) and MVC tests
cd services/api-service && mvn test
```

The suite has 38 tests: backoff and failure-classification unit tests, `JobService` tests with Mockito, repository tests against a real PostgreSQL container, and MockMvc validation tests. Docker must be running for the Testcontainers tests. The tests use an ephemeral database, never your dev data.

**Load tests (k6)**

```powershell
cd tests/k6
k6 run -e TOKEN=<jwt> job-submission-test.js
```

---

## Measured performance

Measured on a single local Windows machine with Docker Desktop, the mock AI provider, one or two API/worker instances. These are single runs, not statistically controlled benchmarks, and a laptop is not a production environment.

| Measurement | Result |
|---|---|
| Job submission, 20 VUs | about 25-28 req/s, P95 125-536 ms across runs, 0% errors |
| End-to-end with 1 worker | 844/844 completed; average queue wait 31.7 s, processing 0.062 s (queue wait is about 99.8% of latency) |
| End-to-end with 2 workers | 887 completed; average queue wait 24.0 s (24% lower), Kafka partitions split 3/3 |

The main finding: submission stays fast, and end-to-end latency is dominated by queueing when arrival rate exceeds the worker fleet's throughput. Adding workers (up to the partition count) or parallelizing within a worker reduces it.

---

## Failure and chaos testing

Seven scenarios were run against the live stack. Full results are in `docs/failure-testing.md`.

| Scenario | Result |
|---|---|
| Kill an API instance | Needs a clean re-run (the test ran with a single backend and returned 502) |
| Kill a worker | Partitions rebalanced to the other worker, lag returned to 0, all 100 jobs completed (exact single-message redelivery not isolated) |
| Redis down | Job status reads fell through to Postgres. The rate limiter did not fail open as designed (open bug) |
| AI provider timeout | Retry, circuit breaker and DLQ verified with logs |
| Kafka consumer restart | Resumed from committed offsets, lag 0 |
| Replica down | No failover. Reads routed to the replica return 500 until it returns |
| MinIO down | RETRY, RETRY, FAILED, DLQ; no false COMPLETED |

---

## Known limitations

- No automatic fallback from the read replica to the primary.
- The rate limiter does not fail open when Redis is unavailable.
- Strict priority polling can starve `LOW` priority jobs.
- A worker processes polled records sequentially.
- Expired idempotency keys are not cleaned up.
- No transactional outbox for the database + Kafka write.
- The local replica setup demonstrates the mechanism but is not production high availability.
- Grafana dashboards are not yet provisioned as code.
- Only the mock AI provider is wired into the worker.
- Unauthenticated access to protected routes returns the Spring default `403` rather than the error envelope.
- `HikariCP` or Kafka connections can stall after the host machine sleeps; restart the services.

---

## Roadmap

**Completed (phases 0-21):** environment, project structure, database and migrations, authentication, job APIs, AI and storage abstractions, Kafka producer and worker, retry and DLQ, Redis cache, rate limiting, idempotency, priority queues, circuit breakers, Nginx load balancing, read replica, Prometheus and Grafana, structured logging, OpenTelemetry, automated tests, k6 load tests, chaos tests.

**Remaining**

| Phase | Topic |
|---|---|
| 22 | Dockerfiles for both services and a single-command full-stack Compose environment |
| 23 | GitHub Actions CI/CD: build, test, image build tagged with commit SHA |
| 24 | Cloud deployment with managed backing services |
| 25 | Kubernetes manifests and a HorizontalPodAutoscaler driven by Kafka lag or CPU |

**Follow-up work:** fix the Redis fail-open bug, add replica failover, provision a Grafana dashboard, bounded parallel processing in workers, priority fairness, idempotency key cleanup, transactional outbox.

---

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `28P01 password authentication failed` | Container credentials drifted after a recreate. Run `docker exec -it ai-platform-postgres psql -U ai_platform_user -d ai_platform -c "ALTER USER ai_platform_user WITH PASSWORD 'devpassword';"` |
| `Could not resolve placeholder '...'` | A config block in `application.yml` is nested under the wrong key. Top-level blocks (`jwt`, `cache`, `rate-limit`, `kafka-topics`, `retry`) must start at column 0 |
| `Port 8080 was already in use` | A previous instance is still running. Find it with `netstat -ano \| findstr :8080` and stop it |
| `illegal character: '\ufeff'` when compiling | A file was written with a UTF-8 BOM. Rewrite it without a BOM |
| Jobs stay `QUEUED` | The worker is not running, crashed, or is not subscribed to the topic. Check the worker log for `PriorityJobPoller started` |
| Docker commands fail with a pipe error | Docker Desktop is not running or WSL2 is not installed |
| Kafka commands refuse offset reset | The consumer group is still active; stop the workers first or wait for the session timeout |

---

## License

Add a license file before publishing (for example MIT).
