# Cloud deployment plan (Phase 24) — NOT YET PROVISIONED

> This document is a plan, not a deployment. No cloud resources have been
> created, no accounts have been opened, and no money has been spent.
> Provisioning is the remaining manual step: pick a provider, supply
> credentials through that provider's secret manager (never in git), and
> follow the steps below.

## Recommended approach for a public demo

Keep the architecture the same and swap local services for managed ones
where it removes operational burden. The cost driver to watch is managed
Kafka — start small and measure before scaling it.

| Layer | Demo choice | Alternative |
|---|---|---|
| API + worker containers | Render or Fly.io, from the GHCR images built by `.github/workflows/ci.yml`, pinned to a commit SHA (e.g. `ghcr.io/prajjaval08/distributed-ai-platform-api-service:<sha>`) | The Kubernetes manifests in `infrastructure/kubernetes/` on any cluster, if you already run one |
| PostgreSQL (primary) | Neon (or Supabase / managed Postgres on your cloud) | Self-hosted Postgres on a VM, with the replica as a second instance |
| PostgreSQL replica | Your Postgres provider's read replica, if available; otherwise point `REPLICA_DB_URL` at the primary temporarily and note the known failover gap in `docs/failure-testing.md` | — |
| Kafka | Confluent Cloud or Aiven for Kafka (trial/free tier to start; single broker, replication factor 1 for a demo, topics as in `infrastructure/docker/docker-compose.yml` via `kafka-init` logic) | Self-hosted Kafka on a VM (cheaper, more ops) |
| Object storage | Cloudflare R2 or AWS S3, S3-compatible (the services already speak S3) | MinIO on a VM |
| Redis | Upstash or your cloud's managed Redis | Self-hosted Redis on a VM |
| Secrets | The provider's secret/environment store (Render env groups, Fly secrets, Kubernetes Secrets via a sealed-secret tool) | — |
| Ingress / public URL | The container platform's public domain first (e.g. `*.onrender.com` / `*.fly.dev`); add a custom domain + TLS only after the demo URL works | Ingress in `infrastructure/kubernetes/ingress.yaml` with a real host and TLS |

## Environment variables to set (exact names the services read)

From `services/*/src/main/resources/application.yml` placeholders.
Set every one of these in the provider's secret store; do not commit a
filled-in `.env`.

- `DB_URL`, `REPLICA_DB_URL` — JDBC URLs for primary and replica
- `POSTGRES_PASSWORD` — database password (shared by both datasources
  in the current code)
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_JOB_CACHE_TTL_SECONDS`
- `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_TOPIC_AI_JOBS`,
  `KAFKA_TOPIC_AI_JOBS_HIGH`, `KAFKA_TOPIC_AI_JOBS_LOW`,
  `KAFKA_TOPIC_AI_JOBS_DLQ`
- `OBJECT_STORAGE_ENDPOINT`, `OBJECT_STORAGE_BUCKET`,
  `OBJECT_STORAGE_ACCESS_KEY`, `OBJECT_STORAGE_SECRET_KEY`,
  `OBJECT_STORAGE_REGION`, `OBJECT_STORAGE_PRESIGNED_EXPIRY_SECONDS`
- `JWT_SECRET` (at least 32 characters, generated — never the local-dev
  default), `JWT_EXPIRATION_MS`
- `RATE_LIMIT_JOBS_PER_HOUR`, `RATE_LIMIT_AUTH_PER_HOUR`
- `AI_PROVIDER_TYPE` (`mock` for the demo unless a real provider key is
  deliberately supplied), `AI_PROVIDER_API_KEY`, `AI_PROVIDER_URL`,
  `AI_PROVIDER_MODEL`
- `AI_PROVIDER_TIMEOUT_SECONDS`, `OBJECT_STORAGE_TIMEOUT_SECONDS`
- `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`
  (or run Jaeger/OTel collector as part of the deployment; tracing is
  optional for a first demo URL, observability is not)

## Steps for the public demo URL

1. Choose the container platform and the managed Postgres/Kafka/storage
   above; record the choice here before spending anything.
2. Create the managed Postgres database, run Flyway migrations
   (the API runs them on startup with `flyway.enabled: true`), and
   create the Kafka topics (high/normal/low, results, failures, DLQ)
   with the partition counts from `kafka-init`.
3. Create the object-storage bucket and the Redis instance.
4. Push a tagged build: merge to `master` so CI publishes
   `ghcr.io/prajjaval08/distributed-ai-platform-{api,worker}-service:<sha>`.
5. Deploy both images with the env vars above, health checks on
   `/actuator/health` (API 8080, worker 8081).
6. Smoke-test the public URL: register, log in, submit a job
   (`POST /api/v1/jobs` → 202 + job ID), poll `GET /api/v1/jobs/{id}`
   to COMPLETED, fetch `GET /api/v1/jobs/{id}/result`.
7. Only then add a custom domain, TLS, and the Grafana/Prometheus
   dashboards; record the demo URL in the README.

## What is deliberately not decided here

Provider choice, region, instance sizes, and budget are not guessed in
this document — they depend on what the owner is willing to spend, and
managed Kafka pricing varies widely. Start on free tiers/trials, measure
with the k6 scripts in `tests/k6/`, and scale the worker count (or the
Kubernetes HPA) from observed consumer lag, not from a forecast.
