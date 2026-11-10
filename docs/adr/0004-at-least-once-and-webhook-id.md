# 4. At-least-once delivery, deduplicated by `webhook-id`

**Status:** accepted

## Context
Exactly-once delivery over HTTP is impossible: a receiver can process a request and time out
before answering. Global ordering would need one in-flight delivery per endpoint, so one slow
event would hold up every other event for that endpoint.

## Decision
HookRelay promises at-least-once delivery and no ordering. Every attempt for an event carries the
same `webhook-id` (the event id), including replays. Receivers deduplicate on it, as the bundled
sample receiver does. Signatures follow Standard Webhooks
(`webhook-id`, `webhook-timestamp`, `webhook-signature: v1,<base64 HMAC-SHA256>`).

## Consequences
- Retries, publisher republishes and replays are safe for receivers that deduplicate.
- Attempt numbers keep counting across replays, so the attempt history is never rewritten.
- Receivers that need ordering must use the payload (version or timestamp), not arrival order.

## Alternatives
- Per-endpoint FIFO: needed only by a minority of consumers; it costs head-of-line blocking for all.
