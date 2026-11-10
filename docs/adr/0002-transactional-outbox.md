# 2. Transactional outbox between MariaDB and RabbitMQ

**Status:** accepted

## Context
Accepting an event writes the event and one delivery per subscribed endpoint, and must cause
work messages to be published. Writing to MariaDB and publishing to RabbitMQ cannot be one
transaction: publish-then-commit can send work for rows that roll back; commit-then-publish loses
work if the process dies between the two.

## Decision
Every state change that needs a message writes an `outbox` row in the same transaction. A relay
(`OutboxRelay`, every 200 ms) claims unpublished rows with `SELECT … FOR UPDATE SKIP LOCKED`,
publishes them with publisher confirms (mandatory, persistent) and marks only the confirmed rows
as published. Rows not confirmed within 5 s stay unpublished and are retried.

## Consequences
- `202 Accepted` means the event and its deliveries are durable; the message will follow.
- Several instances can relay concurrently without double-claiming a row.
- Publishing is at-least-once: a confirm lost after the broker stored the message republishes it.
  Workers drop duplicates by dispatch token (ADR 4).
- `hookrelay_outbox_backlog` / `hookrelay_outbox_lag_seconds` show broker trouble directly.
- **Ceiling:** relay throughput is bounded by one batch of 100 per poll per instance. In the load
  test (README) the backlog peaked under 1,000 rows at 1,600 events/s ingest.

## Alternatives
- **CDC (Debezium) on the binlog**: no polling, but another moving part to run for a demo-sized system.
- **Publish in an `afterCommit` hook**: loses messages on crash; the sweeper (ADR 3) would catch
  them, but only after 15 minutes.
