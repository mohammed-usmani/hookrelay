# 8. Rate limits without Redis

**Status:** accepted (supersedes the original plan to use Bucket4j on MariaDB for outbound limits)

## Context
Two limits exist. *Inbound*: protect the API from one noisy key. *Outbound*: an endpoint may ask
for at most N requests per second. That promise is to the receiver, so it must hold across all
instances. The stack has no Redis, and adding one only for limits was not worth it.

## Decision
- **Outbound:** an `endpoints.rate_next_send_at` column. The claim step already holds the
  endpoint's row lock; it sends only if `now ≥ rate_next_send_at`, then sets
  `rate_next_send_at = now + 1/N s`. Otherwise it re-queues the delivery for the remaining wait,
  which does not count as an attempt. Sends are evenly spaced, and idle time does not bank a burst.
  (Bucket4j's MySQL proxy was tried first. It needs MySQL Connector/J classes at runtime and crashed
  the workers on MariaDB's driver. The column needs no dependency and no extra round trip.)
- **Inbound:** Bucket4j in memory, per API key, per instance (default 100/s, burst 200) → `429`
  with `Retry-After`.

## Consequences
- The outbound limit is exact cluster-wide (`CircuitAndRateLimitIT` checks a 2/s limit across consumers).
- **Ceiling:** the outbound limit serializes sends to one endpoint through its row lock, which is
  fine for the limits receivers ask for (single to low hundreds per second). The inbound limit is
  per instance: with N instances a key can reach N × the limit.

## Alternatives
- Redis token buckets for both: exact and fast, and the first change at larger scale.
- Bucket4j JDBC proxy: see above.
