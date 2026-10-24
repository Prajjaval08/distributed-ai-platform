# Kubernetes deployment (Phase 25)

## What this is — and what it is not

These manifests deploy only the two stateless services: `api-service`
(2 replicas) and `worker-service` (2 replicas, autoscaled to 10 on CPU).
They do **not** deploy PostgreSQL, Redis, Kafka, MinIO, or Jaeger.
The ConfigMaps point at in-cluster hostnames (`postgres`, `redis`,
`kafka:29092`, `minio:9000`, `jaeger:4317`), so before applying you must
provide those as Services in the `ai-platform` namespace — either
self-hosted StatefulSets/Helm releases, or `ExternalName` Services pointing
at managed offerings (see `docs/cloud-deployment.md`). If those Services
do not exist, the API and worker pods will fail their health checks, by
design: they should not report healthy with no database or queue behind
them. The replica database is likewise expected at
`postgres-replica:5432`; if you have no replica yet, point
`REPLICA_DB_URL` at the primary as a temporary measure and treat replica
failover as the known open gap (see `docs/failure-testing.md`), not as
something Kubernetes fixes on its own.

## Apply

```bash
# 1. Replace the placeholders in secret.yaml first — or, preferably,
#    create the Secret from your secret manager (commands in secret.yaml).
# 2. Pin the image tags in api-deployment.yaml and worker-deployment.yaml
#    to a real CI SHA (see .github/workflows/ci.yml), not :latest.
# 3. Replace the Ingress host in ingress.yaml with your real domain, and
#    add TLS before going public.
kubectl apply -k infrastructure/kubernetes/
kubectl -n ai-platform get pods,svc,ingress,hpa
kubectl -n ai-platform logs deploy/api-service
```

## Scaling

- Workers scale on CPU (70% average utilisation, 2–10 replicas) via
  `worker-hpa.yaml`. That scaler works with metrics-server alone.
- Scaling on Kafka consumer lag is **documented but not wired** in
  `worker-hpa.yaml`: it needs a lag exporter + prometheus-adapter, with
  the exact steps in the comments there. Do not claim lag-based scaling
  until that block is uncommented and verified with
  `kubectl get hpa -n ai-platform` showing the external metric.
- The API is not autoscaled here: add an HPA for it (CPU or request
  rate) once you have production traffic figures, rather than guessing
  thresholds now.

## Verification status

These manifests have been YAML-parsed and cross-checked against the
services' `application.yml` env-var names and `/actuator/health`
endpoints, but they have **not** been applied to a cluster in this
environment (no cluster is available here). First apply must be to a
throwaway namespace/cluster, followed by the health-endpoint and
job-submission checks in the main README.
