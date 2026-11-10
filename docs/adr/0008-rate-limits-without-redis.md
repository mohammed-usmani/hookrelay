# 8. Rate limits without Redis

**Status:** accepted (supersedes the original plan to use Bucket4j on MariaDB for outbound limits)

## Context
Two limits exist. *Inbound*: protect the API from one noisy key. *Outbound*: an endpoint may ask
for at most N requests per second. That promise is to the receiver, so it must hold across all
instances. The stack has no Redis, and adding one only for limits was not worth it.

## Decision
- **Outbound:** an `endpoints.rate_next_send_at` column holds the next free send slot (slots are
  1/N s apart; idle time banks no burst). The claim step already holds the endpoint's row lock. If
  the next slot is within one second, the claim *reserves* it (advances the column) and the worker
  waits until the slot, then sends: spacing is exact and throughput is the full N/s. If the next free
  slot is further out, the delivery is deferred through a delay queue, which does not count as an
  attempt. The deferral picks a random point within the time the endpoint's current backlog needs to
  drain, so a backlog neither re-queues every second nor comes back all at once.
  (Bucket4j's MySQL proxy was tried first. It needs MySQL Connector/J classes at runtime and crashed
  the workers on MariaDB's driver. The column needs no dependency and no extra round trip.)
- **Inbound:** Bucket4j in memory, per API key, per instance (default 100/s, burst 200) → `429`
  with `Retry-After`.

## Consequences
- The outbound limit is exact cluster-wide. `CircuitAndRateLimitIT` checks that 2/s is never exceeded
  across consumers, and that 30 events at 10/s go out in about 3 s.
- A worker thread may sleep up to one second for its reserved slot.
- Deferral counts the endpoint's waiting deliveries, an indexed count per deferral.
- **Ceiling:** the outbound limit serializes sends to one endpoint through its row lock, which is
  fine for the limits receivers ask for (single to low hundreds per second). The inbound limit is
  per instance: with N instances a key can reach N × the limit.

## Alternatives
- Redis token buckets for both: exact and fast, and the first change at larger scale.
- Bucket4j JDBC proxy: see above.
