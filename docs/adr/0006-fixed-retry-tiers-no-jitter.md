# 6. Fixed retry schedule over fixed delay tiers, no jitter

**Status:** accepted

## Context
Failed deliveries are retried over hours. RabbitMQ has no native per-message delay: per-message
TTL only expires at the head of a queue, so messages with mixed delays block each other.

## Decision
Retry schedule (defaults): `5s, 5m, 30m, 2h, 5h, 10h, 10h`, which gives 8 attempts over about 27½
hours, inside a 3-day delivery window. Delays are served by fixed queues with one TTL each
(`1s, 5s, 30s, 5m, 30m, 1h, 2h, 5h, 10h`); a delay maps to the smallest tier at least as long. The
schedule is configuration (`hookrelay.delivery.retry-schedule`); the demo profile uses seconds.

## Consequences
- No head-of-line blocking: every message in a tier queue has the same TTL.
- No jitter. Synchronized retries against a recovering receiver are limited by the per-endpoint
  circuit breaker (10 consecutive failures → open; cooldowns 5m/30m/1h; a single half-open probe)
  and the optional per-endpoint rate limit. Deferrals caused by these never count as attempts.
- **Ceiling:** delays round up to a tier, so a custom schedule gets tier precision, not exact delays.

## Alternatives
- The delayed-message-exchange plugin: exact delays, but a non-core plugin whose delayed
  messages are not replicated.
- Polling the database for due deliveries: see ADR 1.
