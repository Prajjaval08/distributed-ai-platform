# Failure testing (Phase 21 chaos results)

Status as of 24 Oct 2026. This is the observed-behaviour record for the
failure scenarios in the design document — hypotheses until verified
against the running system, and the table below is what was actually
observed. Single runs, not a controlled benchmark.

## Confirmed gaps (open work, not fixed in this pass)

| Scenario | Observed behaviour | Follow-up |
|---|---|---|
| Database replica down | `GET /jobs` returns 500. No replica failover exists. An early apparent success came from a stale pooled connection, not from failover. | Add a replica health check and primary fallback in `RoutingDataSource` (`services/api-service/src/main/java/com/distributedai/api/config/RoutingDataSource.java`). Until then, `REPLICA_DB_URL` in Kubernetes can be pointed at the primary as a temporary measure (see `infrastructure/kubernetes/README.md`). |
| Redis down | The rate limiter did **not** fail open, contrary to the Phase 10 design. Root cause not yet isolated. | Fix `RateLimiter` (`services/api-service/src/main/java/com/distributedai/api/ratelimit/RateLimiter.java`): catch the Lettuce exception and return allowed. Not attempted here because it needs a live Redis-down run to verify. |

## Behaved as designed

| Scenario | Observed behaviour |
|---|---|
| Kafka rebalance / consumer restart | Lag returned to 0. |
| MinIO outage | Jobs went RETRY, RETRY, FAILED, then DLQ (`ai-jobs-dlq`). No false success was reported. |

## Partial / needs a clean re-run

| Scenario | Observed behaviour |
|---|---|
| API instance killed | The test ran with a single backend (502 from the load balancer), so it needs a clean re-run with two backends up. |
| Single-message mid-processing redelivery | Not isolated in the Phase 21 run. |
| AI provider timeout | Verified earlier, in Phases 8 and 13 (retry/backoff, Resilience4j timeout + circuit breaker), not re-proven in Phase 21. |

## Other known open items (from the Phase 21 status, not re-tested here)

- Phase 16 leftovers: confirm business metrics appear in Prometheus and
  provision a Grafana dashboard JSON.
- Strict priority polling can starve LOW jobs; fairness/aging is not built.
- Workers process records sequentially; bounded parallelism with safe
  offset commits would cut queue wait further.
- Idempotency-key cleanup job for expired keys is not built.
- Transactional outbox is not built (DB-write plus Kafka-publish
  dual-write gap).
- `OpenAiImageProvider` is not wired into worker-service; only the mock is.
- Windows sleep/suspend stalled Kafka consumers during testing; treated
  as an environment issue, not a bug.
- The dead `JobNotProcessableException` code and the `FORCE_FAIL` test
  hook in `MockAiProvider` were removed in the Phase 22–25 pass (no test
  depended on either — verified by usage search before removal).

## How to re-run

From `infrastructure/docker/`, with the full stack up
(`docker compose up --build`), kill or stop one dependency at a time,
exercise the affected path, and record the observed (not expected)
behaviour in the table above before claiming the gap is closed.
