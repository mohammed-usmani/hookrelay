# 3. The database is the source of truth; the queue is a hint

**Status:** accepted

## Context
Queue messages can be lost (purged queue, broker data loss), duplicated (publisher retry,
redelivery after a worker crash) or outdated (a delivery was replayed, its endpoint deleted).

## Decision
A delivery's state lives only in its `deliveries` row. A queue message carries just
`{deliveryId, dispatchToken}`. Each time a delivery is queued, it gets a fresh dispatch token
and `next_attempt_at` set to when the message should arrive. A worker first *claims* the delivery
under a row lock: the claim succeeds only if the token matches and the delivery is unfinished. All
decisions are made under that lock: expired, endpoint deleted or paused, circuit open, rate
limited. A `StuckDeliverySweeper` (every minute, ShedLock) re-queues deliveries more than 15
minutes past `next_attempt_at` that have nothing waiting in the outbox.

## Consequences
- Duplicate or outdated messages are dropped cheaply; replay simply issues a new token.
- A lost message delays a delivery by at most about 15 minutes; it is never lost.
  Tested by purging the work queue (`JobsIT`).
- Expiry of parked deliveries is a sweep too, because parked deliveries never reach a worker.
- **Ceiling:** every attempt costs two short transactions (claim, record result).

## Alternatives
- Full state in the message: no database round trip, but replay, cancel and deletion would need
  to chase messages already in queues.
