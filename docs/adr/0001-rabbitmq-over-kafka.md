# 1. RabbitMQ, not Kafka, for the delivery queue

**Status:** accepted

## Context
Each delivery is an independent unit of work that may wait seconds to hours before its next
attempt. Workers must be able to pick up any delivery, retry it later and park it, without
blocking the deliveries behind it. Ordering between deliveries is not promised (see ADR 4).

## Decision
RabbitMQ 4 with quorum queues: one work queue, a set of fixed delay queues (`deliveries.delay.<tier>`)
whose messages dead-letter back into the work exchange when their TTL expires, and a dead-letter
queue that drives notifications. Workers use manual acks and a bounded prefetch.

## Consequences
- Delayed retries come from plain broker features (per-queue TTL + dead-lettering), with no plugin
  and no scheduler polling the database.
- Competing consumers scale horizontally; a slow receiver only holds the worker threads it occupies.
- Quorum queues replicate when the broker is clustered; the demo runs a single node.
- **Ceiling:** one work queue is shared by every endpoint, so a very large burst for one endpoint
  delays the others behind it (the circuit breaker and per-endpoint rate limit soften this).

## Alternatives
- **Kafka**: strong ordering per partition and replay, but per-message delays and
  "skip this one, retry it in 30 minutes" fight the log model (you need retry topics per delay
  and a consumer that can't commit past a stuck record). Kafka partitioned by endpoint is the
  scale-up path (README, "How I'd scale it").
- **Database polling** (`SELECT … FOR UPDATE SKIP LOCKED` as the queue): simpler, but turns
  MariaDB into the hot path for every attempt and every wait.
