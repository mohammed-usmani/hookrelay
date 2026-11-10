# HookRelay — design

A webhook delivery service: other applications post events to it once, and it
reliably delivers them to their subscribers' HTTPS endpoints, with signing,
retries, a dead-letter queue, replay, and a full delivery log.

Date: 2026-11-10. Status: draft for approval.

## 1. Why this exists

Résumé project #1 for a fresher Java backend candidate targeting mid-size
product companies, banks and GCCs (PayPal, Barclays, Citi, LSEG, Optum…) whose
hiring leans on the résumé + technical interview rather than a screening exam.

It must:

- read in a 30-second résumé skim as "built real backend infrastructure",
  not "followed a CRUD tutorial";
- survive a 20-minute interview deep-dive — every component has a reason the
  candidate can explain;
- deliberately cover the skill gaps found in the candidate's job survey and
  rejection analysis (`~/dev/personal/jobspy`): messaging + DLQs (a rejection
  was attributed to missing Kafka/RabbitMQ/DLQ), Docker, unit/integration
  testing, Spring Security, concurrency, CI/CD.

The user's bar, in their words: "make sure this looks like proper system, not
just an MVP or demo." Time is not a constraint. A frontend will be added
later, once the backend is complete — the API must be ready for it.

## 2. Goals, non-goals, anti-goals

**Goals**
- Complete, production-shaped backend: multi-tenant, authenticated,
  horizontally scalable (N identical instances), observable, tested.
- At-least-once delivery that never loses an accepted event, even if
  RabbitMQ or the app crashes mid-flight.
- One-command local run (`docker compose up`) and a scripted demo.
- Documentation an interviewer can read: README with architecture diagram,
  short ADRs for each major decision, measured load-test numbers.

**Non-goals (now)**
- Web UI (comes later as its own piece of work).
- User accounts / login / RBAC for humans — machine auth only (admin token +
  API keys). The future dashboard will need this; not designed here.
- Kafka, Redis, Kubernetes, multi-region, billing.
- Ordered delivery and exactly-once delivery (documented as impossible /
  out of scope; receivers dedupe on `webhook-id`).
- Client SDKs beyond the one Java verification helper.

**Anti-goals** — it fails even if it works when:
- it contains anything the candidate can't explain (features for show);
- it has demo-ware habits: hardcoded secrets, swallowed exceptions, untested
  core paths, `ddl-auto=update`;
- the README claims anything not measured or not implemented (the rejection
  analysis already flags overstated résumé claims as a risk).

## 3. Decided stack (assistant's call, user delegated it)

| Area | Choice | Reason |
|---|---|---|
| Language/runtime | Java 21 (installed: OpenJDK 21.0.10) | Current LTS |
| Framework | Latest stable Spring Boot **4.x** (assistant's call: 3.5's OSS support ended mid-2026; 4.x brings Jackson 3 / Spring Security 7 — pin the exact version at build start) | Most-asked framework in survey |
| Build | Maven, via Maven Wrapper (`mvnw`) — Maven is not installed globally | Maven appears more than Gradle in JDs |
| DB | MariaDB 11.4 LTS, Spring Data JPA/Hibernate | Required by user; JPA is in JDs. 11.4 has `SKIP LOCKED` (≥10.6) |
| Migrations | Flyway; `ddl-auto=validate` | Schema is versioned, never auto-mutated |
| Broker | RabbitMQ (3.13+/4.x, management plugin) | Per-message retries, TTL, dead-letter exchanges are native; Kafka is a log, awkward for per-message retry. "Why not Kafka" is an ADR |
| Security | Spring Security; API keys; HMAC-SHA256 signing | |
| HTTP client for delivery | JDK `HttpClient` (or Spring `RestClient` over it) | No extra dependency |
| JSON Schema validation | networknt `json-schema-validator` | For event-type payload schemas |
| Rate limiting | Bucket4j with its MySQL/MariaDB JDBC backend | Correct across instances without adding Redis |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (MariaDB, RabbitMQ), WireMock, Awaitility | Real infra in tests |
| API docs | springdoc-openapi (Swagger UI) | |
| Observability | Actuator, Micrometer → Prometheus, provisioned Grafana dashboard, JSON logs with MDC ids | |
| Packaging | Multi-stage Dockerfile, Docker Compose | |
| CI | GitHub Actions: build, test, build image | |
| Load test | k6 | Numbers go in README |

Project location: `~/dev/personal/hookrelay`, Maven multi-module:
`hookrelay-server` (the service), `hookrelay-receiver` (sample receiver app +
signature-verification helper). Docker runtime is OrbStack (Docker 29.8.2,
10 CPUs, 8 GB); `/var/run/docker.sock` points at it, so Testcontainers
works with no extra config.

## 4. Domain model

- **Application** — a tenant (a sender, e.g. "Acme Shop"). Has name, status.
- **ApiKey** — belongs to an Application. Format `hr_live_<prefix>_<secret>`;
  stored as prefix + SHA-256 hash of the secret (secret is high-entropy, so a
  fast hash is correct; bcrypt would only slow every request — ADR-worthy).
  Shown once on creation. Can be revoked; multiple active keys allow rotation.
- **EventType** — per application, e.g. `order.created`; description,
  optional JSON Schema, archived flag. Events of unknown or archived types are
  rejected.
- **Endpoint** — per application: URL, description, subscribed event types
  (empty = all), status (`ACTIVE`, `PAUSED` by user, `DISABLED` by system),
  optional rate limit (requests/sec), signing secret(s), circuit-breaker
  state.
- **Event** — immutable metadata: id (ULID or UUIDv7, time-sortable),
  application, type, created_at. Payload (JSON, ≤ 256 KB) is never edited,
  only purged by retention (payload set NULL, `payload_purged_at` set).
- **IdempotencyKey** — separate table: (application, key) unique, SHA-256 of
  the request body, the event id it produced, created_at. Pruned after 24 h
  by the retention job (MariaDB can't express a time-windowed unique
  constraint). Same key + same body → original response; same key +
  different body → `422`.
- **Delivery** — one per (event × matching endpoint): status `PENDING`,
  `SUCCEEDED`, `FAILED_RETRYING`, `DEAD`; attempt count; next_attempt_at;
  last error summary.
- **Attempt** — one per HTTP try: delivery, number, started_at, duration,
  response status, truncated response body (≤ 4 KB), error class
  (timeout / connection / TLS / non-2xx / blocked-by-SSRF-guard).
- **OutboxMessage** — written in the same transaction as the Event and its
  Deliveries; holds the delivery ids to publish; `published_at` nullable.

## 5. Core flows

**Ingest** — `POST /v1/events` (API key):
1. Authenticate, resolve application.
2. If `Idempotency-Key` header seen in last 24 h for this app → same body:
   return the original response (same event id), no new work; different
   body: `422`.
3. Validate type exists/not archived; validate payload against schema if set;
   enforce size limit.
4. One DB transaction: insert Event, one Delivery per matching ACTIVE/PAUSED
   endpoint, OutboxMessage rows. Commit.
5. Return `202 Accepted` with event id and delivery count.

**Outbox relay** — scheduled poller on every instance:
`SELECT … FOR UPDATE SKIP LOCKED LIMIT n` on unpublished outbox rows,
publish each delivery id to the work exchange with publisher confirms, mark
published. Safe with N instances; if RabbitMQ is down, rows wait. Crash
between publish and mark → duplicate publish, absorbed by the idempotent
consumer.

**Delivery worker** — RabbitMQ consumer (manual ack, bounded concurrency):
1. Load Delivery; if already SUCCEEDED/DEAD, ack and stop (idempotent).
2. Endpoint PAUSED/DISABLED → park (stays `PENDING`/`FAILED_RETRYING`, not
   attempted; resumed endpoints get parked deliveries re-enqueued).
3. Circuit open → defer to a delay queue without consuming an attempt.
4. Rate limit exhausted → short defer without consuming an attempt.
5. SSRF check on the resolved IP *at send time* (DNS rebinding).
6. Sign and POST with timeouts (connect 5 s, total 15 s). No redirects
   followed.
7. Record Attempt. 2xx → SUCCEEDED. Otherwise → schedule retry, or DEAD if
   the schedule is exhausted (published to the DLQ and marked DEAD).
8. Ack only after the DB write commits.

**Delivery window** — every delivery has a hard deadline: created_at +
3 days (configurable, same value as the endpoint-disable threshold). Time
spent deferred (circuit open, rate-limited, endpoint paused) counts against
it, so no delivery cycles forever. Past the deadline → DEAD with reason
`expired`.

**DLQ queue has a consumer.** A delivery that goes DEAD is published to the
`deliveries.dead` queue; the DLQ consumer increments the dead-letter metric
and emits the `hookrelay.delivery.dead` notification event (section
"Notifications"). The DB status is the source of truth for the API; the
queue is the trigger for side effects, so it drains and never grows
unbounded.

**Delay tiers** — one fixed set of TTL queues serves retries and deferrals:
1 s, 5 s, 30 s, 5 min, 30 min, 1 h, 2 h, 5 h, 10 h. Any deferral picks the
smallest tier ≥ the wanted wait. Circuit cooldowns are tier values
(5 min → 30 min → 1 h) so they line up exactly. Rate-limited deliveries
defer by Bucket4j's estimated wait (rounded up to a tier) rather than
spinning on the 1 s tier; a large backlog against a very slow endpoint still
cycles through the broker — a known ceiling, noted in the ADR.

**Retry schedule** (attempts after the first): 5 s, 5 min, 30 min, 2 h, 5 h,
10 h, 10 h — 8 attempts over ~27 h, matching the Standard Webhooks /
Svix-style schedule. Configurable; the demo profile uses a compressed
schedule (seconds). Implemented as one TTL'd delay queue per tier that
dead-letters back into the work exchange (per-queue TTL avoids the
head-of-line blocking of per-message TTL). Assistant's call: no jitter —
fixed tiers can't jitter, and the circuit breaker already prevents a
recovering receiver being stampeded; this trade-off goes in an ADR.

**Circuit breaker** (per endpoint, state in DB so all instances agree):
closed → open after 10 consecutive failed attempts; open for a cooldown
(5 min, doubling up to 1 h); half-open lets one probe through; success
closes it. If an endpoint has had no success for 3 days (configurable), it
becomes `DISABLED` and the app is notified. On disable, its remaining
PENDING/FAILED_RETRYING deliveries go DEAD with reason `endpoint_disabled`
(replayable later). The application (API key) can re-enable it via
`resume`, which sets ACTIVE and resets the circuit to closed.

**Notifications (dogfooding)** — system event types
`hookrelay.endpoint.disabled`, `hookrelay.delivery.dead` and
`hookrelay.test` are emitted into the application's own stream. Rules:
- `hookrelay.` is a reserved prefix: these types are auto-created for every
  application and cannot be created, edited or archived by users, nor
  posted via `POST /v1/events`.
- An endpoint subscribed to "all" (empty list) does **not** receive system
  events; it must list them explicitly.
- A dead delivery *of a system event* never emits `delivery.dead`
  (prevents loops); it's only logged and counted.

**Replay** — single delivery (any final state), or bulk per endpoint:
"all DEAD since timestamp". Replay resets the delivery to PENDING with a
fresh attempt count and a fresh delivery window (full schedule again), via
the outbox. It keeps the same `webhook-id` — it is the same event, and the
point of that id is that receivers don't process it twice; replaying a
SUCCEEDED delivery is therefore allowed but documented as "receivers that
dedupe will ignore it". If the payload was purged: single replay → `410
Gone`; bulk replay skips it and reports the skipped count.

**Test send** — `POST /v1/endpoints/{id}/test` sends a synthetic
`hookrelay.test` event.

**Retention job** — nightly: purge payloads older than 30 days (keep
metadata), delete attempts older than 90 days, delete idempotency keys
older than 24 h, delete published outbox rows older than 7 days; batched
deletes, safe across instances (ShedLock or a DB lease).

## 6. Signing — Standard Webhooks spec

Follow https://www.standardwebhooks.com: headers `webhook-id`,
`webhook-timestamp`, `webhook-signature: v1,<base64 HMAC-SHA256 of
"id.timestamp.body">`. Secrets `whsec_<base64>`, generated server-side,
stored encrypted at rest (AES-GCM, key from env). **Rotation:** the new
secret is active immediately; the old one keeps signing too (both signatures
in the header, space-separated, as the spec allows) for 24 h, then is
dropped. The sample receiver verifies signatures and rejects timestamps older
than 5 minutes (replay protection).

## 7. Security

- Two auth levels: **admin token** (from env) for managing applications and
  their API keys; **API key** for everything scoped to one application.
  Tenant isolation is enforced in every query (tests must prove app A can't
  read app B's anything — return 404, not 403).
- **SSRF guard** at endpoint registration and at send time: reject loopback,
  RFC 1918 private, link-local (incl. 169.254.169.254), CGNAT, multicast,
  unspecified and their IPv6 equivalents. Two dev/test-only settings, both
  off in `prod`: `allow-http` and `allowed-cidrs` (e.g. the Compose network
  and loopback for WireMock). Tests cover both the block and the allowlist.
- Secrets never logged; API keys hashed; signing secrets encrypted.
- Inbound API rate limit per API key: Bucket4j **in memory, per instance**
  (protection only, so approximate is fine; effective limit = configured ×
  instances, documented). Outbound per-endpoint limits use Bucket4j on
  MariaDB because they are a promise to the receiver and must hold across
  instances. 429 with `Retry-After`.

## 8. API

Versioned under `/v1`. JSON. Errors as RFC 9457 problem details. Cursor
pagination on all lists. OpenAPI spec generated; Swagger UI enabled.

- Admin: create/list/get/update applications; create/list/revoke API keys.
- Event types: CRUD + archive.
- Endpoints: CRUD, pause/resume, rotate secret, get secret, test send,
  bulk replay, stats (success rate, p50/p95 latency, last 24 h).
- Events: create (ingest), list (filter by type, time), get.
- Deliveries: list (filter by status, endpoint, event, time), get, list
  attempts, replay. "DLQ" in the API is `deliveries?status=DEAD`.

The list/filter/stats endpoints exist so the future dashboard needs no
backend changes.

## 9. Operations & observability

- Actuator health with DB and RabbitMQ indicators; readiness/liveness.
- Metrics: events ingested, deliveries by outcome, attempt latency
  histogram, outbox backlog/lag, circuit state counts, queue depths.
- Grafana dashboard provisioned from a JSON file in the repo.
- JSON logs; `event_id`, `delivery_id`, `application_id` in MDC.
- Graceful shutdown: stop consuming, finish in-flight attempts, then exit.
- All config through env vars with documented defaults; profiles `dev`,
  `demo` (compressed retries), `prod`.

## 10. Testing & proof

- Unit tests for pure logic: signing (against Standard Webhooks test
  vectors), SSRF IP classification, retry schedule, circuit breaker state
  machine, idempotency.
- Integration tests with Testcontainers (MariaDB + RabbitMQ) and WireMock
  receivers, covering end to end: success; fail-then-succeed; exhaust → DEAD
  → replay → success; circuit opens and recovers; paused endpoint parks and
  resumes; idempotent ingest; tenant isolation; SSRF rejection; outbox
  survives RabbitMQ being down at ingest time; duplicate message delivered
  once to the consumer path.
- Two app instances in one integration test to prove no double sends from
  the outbox relay.
- k6 load test script; README reports measured ingest throughput and
  end-to-end delivery latency on the candidate's machine, with hardware
  stated.
- CI runs everything on each push.

## 11. Deliverables

- `hookrelay-server`, `hookrelay-receiver` (with chaos toggles: failure
  rate, added latency, forced status — for the demo), Dockerfile,
  `docker-compose.yml` (MariaDB, RabbitMQ, server ×2, receiver, Prometheus,
  Grafana).
- `scripts/demo.sh`: creates an app, endpoint, fires events, breaks the
  receiver, shows retries → DEAD → replay.
- README: what/why, architecture diagram (Mermaid), quick start, demo,
  API overview, design decisions, measured numbers, "how I'd scale it"
  (Kafka, partitioned workers, Redis rate limits).
- `docs/adr/`: short ADRs — RabbitMQ over Kafka; transactional outbox; DB as
  source of truth vs broker; at-least-once + `webhook-id`; SHA-256 for API
  keys; fixed retry tiers without jitter; SSRF at send time; Bucket4j on
  MariaDB for outbound limits (row lock per send — throughput ceiling
  stated) and in-memory for inbound, instead of Redis.

## 12. Left to the builder

Package layout, class names, exact table/column names and indexes, ULID vs
UUIDv7, exact metric names, ShedLock vs custom lease, prefetch/concurrency
numbers, Grafana panel layout, how parked deliveries are re-enqueued on
resume.
