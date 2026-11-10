# HookRelay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build HookRelay, a multi-tenant webhook delivery service (ingest → transactional outbox → RabbitMQ → signed HTTP delivery with retries, circuit breaker, DLQ, replay), production-shaped and fully tested.

**Architecture:** One Spring Boot app (`hookrelay-server`) run as N identical instances. MariaDB is the source of truth; RabbitMQ carries delivery work, TTL delay tiers, and a dead-letter queue. A second module (`hookrelay-receiver`) is a sample receiver with signature verification and chaos toggles for demos and tests.

**Tech Stack:** Java 21, Spring Boot 4.x (Web MVC, Data JPA, AMQP, Security, Actuator), MariaDB 11.4, Flyway, RabbitMQ 4.x, Bucket4j, ShedLock, networknt json-schema-validator, springdoc-openapi, Micrometer/Prometheus/Grafana, JUnit 5, Testcontainers, WireMock, Awaitility, k6, Docker Compose, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-10-hookrelay-design.md` — read it before any task; this plan argues from it.

## Global Constraints

- Java 21; Maven via wrapper (`./mvnw`) — no global Maven.
- Spring Boot: latest stable 4.x, version pinned in the parent POM in Task 1. All other libraries: the newest version that declares Boot 4 / Jackson 3 compatibility; verify on Maven Central, don't guess.
- MariaDB 11.4; `spring.jpa.hibernate.ddl-auto=validate`; every schema change is a new Flyway file `V<n>__<name>.sql`.
- Base package `dev.hookrelay.server`, split by feature, not by layer. Receiver: `dev.hookrelay.receiver`.
- All config via env vars bound to one `@ConfigurationProperties("hookrelay")` record tree (`HookRelayProperties`); profiles `dev`, `demo`, `prod`, `test`.
- API under `/v1`, JSON, errors as RFC 9457 `application/problem+json`, cursor pagination on every list.
- Other tenants' resources return 404, never 403.
- Payload ≤ 256 KB (262,144 bytes); stored and sent **byte-for-byte as received**.
- Retry schedule (after first attempt): 5s, 5m, 30m, 2h, 5h, 10h, 10h. Delay tiers: 1s, 5s, 30s, 5m, 30m, 1h, 2h, 5h, 10h.
- Circuit: open after 10 consecutive failures; cooldowns 5m → 30m → 1h. Delivery window and endpoint-disable threshold: 3 days.
- HTTP send: connect timeout 5s, total 15s, no redirects, response body stored ≤ 4 KB.
- Retention: payloads 30d, attempts 90d, idempotency keys 24h, published outbox rows 7d.
- Signing: Standard Webhooks (`webhook-id`, `webhook-timestamp`, `webhook-signature: v1,<b64>`), secrets `whsec_<b64>`, rotation overlap 24h.
- Never log secrets, API keys, or payload bodies.
- Commit messages: plain imperative, **no Co-Authored-By or AI attribution lines** (user rule).

## Review Focus

1. **Receiver answers 301/302/307** → counted as a failed attempt (`error=REDIRECT`), never followed. Test owned by Task 10.
2. **Receiver hangs or streams a huge body** → attempt ends at 15s as `TIMEOUT`; body read stops at 4 KB, no memory blow-up. Test owned by Task 10.
3. **Endpoint hostname re-resolves to a private IP after registration (DNS rebinding)** → send blocked, attempt `error=SSRF_BLOCKED`, counts as a failure. Test owned by Task 11.
4. **Endpoint deleted while deliveries are pending / in delay queues** → those deliveries go `DEAD` with reason `endpoint_deleted`; worker never throws on a missing endpoint. Test owned by Task 14.
5. **Payload with unicode, odd whitespace, key order** → the receiver gets the exact bytes posted and the signature verifies. Test owned by Task 11.

---

## File structure

```
pom.xml                                  parent (modules, Boot version, plugin mgmt)
mvnw, .mvn/                              wrapper
docker-compose.yml                       full stack (Task 18)
docker-compose.dev.yml                   MariaDB + RabbitMQ only (Task 1)
ops/prometheus.yml, ops/grafana/...      Task 17
scripts/demo.sh                          Task 18
loadtest/ingest.js                       Task 19
docs/adr/NNNN-*.md, README.md            Task 19
.github/workflows/ci.yml                 Task 18
hookrelay-server/
  Dockerfile
  src/main/java/dev/hookrelay/server/
    HookRelayApplication.java
    config/       HookRelayProperties, RabbitTopology, JacksonConfig, Clock bean
    common/       Ids, ProblemHandler, CursorPage, Cursor, NotFound/Conflict exceptions
    tenant/       Application, ApiKey, repos, ApplicationService, ApiKeyService, AdminController
    security/     SecurityConfig, ApiKeyAuthFilter, AdminTokenFilter, TenantContext, InboundRateLimiter
    eventtype/    EventType, repo, EventTypeService, EventTypeController, SchemaValidator
    endpoint/     Endpoint, EndpointSecret, repos, EndpointService, EndpointController, SsrfGuard
    crypto/       SecretCipher, WebhookSigner
    event/        Event, IdempotencyRecord, repos, IngestService, EventController
    outbox/       OutboxMessage, repo, OutboxRelay
    delivery/     Delivery, Attempt, repos, DeliveryWorker, WebhookSender, RetryPolicy, DelayTiers,
                  DeliveryDispatcher, CircuitBreaker, OutboundRateLimiter, DeliveryController, ReplayService
    notify/       SystemEvents, DeadLetterConsumer, EndpointDisabler
    jobs/         RetentionJob, ExpirySweeper, StuckDeliverySweeper, ShedLockConfig
    stats/        EndpointStatsService
  src/main/resources/application.yml, application-{dev,demo,prod,test}.yml, db/migration/V*.sql
  src/test/java/dev/hookrelay/server/...  mirrors main; IntegrationTest base in support/
hookrelay-receiver/
  Dockerfile
  src/main/java/dev/hookrelay/receiver/ ReceiverApplication, WebhookVerifier, ReceiverController, ChaosSettings
```

---

### Task 1: Project scaffold, dev infra, test base

**Files:**
- Create: `pom.xml`, `hookrelay-server/pom.xml`, `hookrelay-receiver/pom.xml` (empty Boot app for now), `mvnw` + `.mvn/`, `.gitignore`, `docker-compose.dev.yml`
- Create: `hookrelay-server/src/main/java/dev/hookrelay/server/HookRelayApplication.java`, `config/HookRelayProperties.java`, `config/ClockConfig.java`
- Create: `src/main/resources/application.yml`, `application-test.yml`, `db/migration/V1__baseline.sql` (empty comment)
- Test: `src/test/java/dev/hookrelay/server/support/IntegrationTest.java`, `HealthIT.java`

**Interfaces:**
- Produces: `abstract class IntegrationTest` — `@SpringBootTest(webEnvironment=RANDOM_PORT)`, `@ActiveProfiles("test")`, static `MariaDBContainer("mariadb:11.4")` and `RabbitMQContainer("rabbitmq:4-management")` with `@ServiceConnection`, containers shared across all ITs (singleton pattern, started once). Exposes `RestClient client()` bound to the random port, and `protected Clock`-override hook via a `@TestConfiguration MutableClock` bean (`MutableClock.advance(Duration)`).
- Produces: `record HookRelayProperties(String adminToken, String encryptionKey, Delivery delivery, Ssrf ssrf, Retention retention, RateLimit rateLimit)` — nested records added by later tasks; this task defines the root and `adminToken`, `encryptionKey` (both required, fail fast at startup if blank).
- Produces: a `java.time.Clock` bean; all time reads in the codebase go through it.

- [ ] **Step 1:** Generate the parent + modules (start.spring.io values: Maven, Java 21, latest Boot 4.x; deps: webmvc, data-jpa, amqp, security, actuator, validation, flyway + flyway-mysql, mariadb driver, testcontainers mariadb/rabbitmq). Add the Maven wrapper. Pin versions in the parent `dependencyManagement`.
- [ ] **Step 2:** Write `docker-compose.dev.yml`: `mariadb:11.4` (db `hookrelay`, healthcheck) on 3306 and `rabbitmq:4-management` on 5672/15672, both bound to `127.0.0.1`.
- [ ] **Step 3: Write failing test** `HealthIT.healthReportsDbAndRabbitUp`:
```java
var body = client().get().uri("/actuator/health").retrieve().body(JsonNode.class);
assertThat(body.at("/status").asText()).isEqualTo("UP");
assertThat(body.at("/components/db/status").asText()).isEqualTo("UP");
assertThat(body.at("/components/rabbit/status").asText()).isEqualTo("UP");
```
(`application-test.yml` sets `management.endpoint.health.show-components=always`.)
- [ ] **Step 4:** `./mvnw -pl hookrelay-server verify -Dit.test=HealthIT` → FAIL (no app / config).
- [ ] **Step 5:** Implement app class, properties, clock bean; Failsafe plugin runs `*IT`, Surefire runs `*Test`.
- [ ] **Step 6:** Same command → PASS. Also `./mvnw verify` from root → BUILD SUCCESS.
- [ ] **Step 7:** Commit `Scaffold multi-module project with dev infra and IT base`.

---

### Task 2: Common building blocks

**Files:**
- Create: `common/Ids.java`, `common/ProblemHandler.java`, `common/CursorPage.java`, `common/Cursor.java`, `common/NotFoundException.java`, `common/ConflictException.java`
- Test: `common/IdsTest.java`, `common/CursorTest.java`, `common/ProblemHandlerIT.java`

**Interfaces:**
- Produces: `Ids.newId(): UUID` — UUIDv7 (time-ordered) from `Clock` + `SecureRandom`; implement directly (48-bit ms timestamp, version 7, variant bits, random rest).
- Produces: `record CursorPage<T>(List<T> data, String nextCursor)`; `Cursor.encode(Instant createdAt, UUID id): String` (opaque base64url) / `Cursor.decode(String): Cursor.Position`. Lists order by `(created_at DESC, id DESC)`; `limit` default 50, max 200.
- Produces: `NotFoundException(String what)` → 404, `ConflictException` → 409, bean-validation errors → 400 with `errors[]` field list, `HttpMessageNotReadable` → 400. All as `ProblemDetail` with `type` = `https://hookrelay.dev/problems/<slug>`.

- [ ] **Step 1: Failing tests:**
  - `IdsTest.isVersion7AndTimeOrdered`: `id.version()==7`, variant 2, and 1,000 ids generated across a clock advance sort in creation order.
  - `CursorTest.roundTrips`: decode(encode(t,id)) equals input; `decode("garbage")` throws `IllegalArgumentException` (mapped to 400).
  - `ProblemHandlerIT.unknownRouteIsProblemJson`: GET `/v1/nope` with admin token → 404, content-type `application/problem+json`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `Add ids, cursor pagination and problem-details errors`.

---

### Task 3: Applications, API keys, security

**Files:**
- Create: `db/migration/V2__tenants.sql`, `tenant/*`, `security/SecurityConfig.java`, `security/AdminTokenFilter.java`, `security/ApiKeyAuthFilter.java`, `security/TenantContext.java`
- Test: `tenant/ApiKeyServiceTest.java`, `tenant/AdminApiIT.java`, `security/AuthIT.java`

**Interfaces:**
- Tables: `applications(id UUID PK, name, status ENUM('ACTIVE','DISABLED'), created_at, updated_at)`, `api_keys(id UUID PK, application_id FK, prefix CHAR(8) UNIQUE, secret_hash BINARY(32), name, created_at, last_used_at NULL, revoked_at NULL)`.
- Produces: `ApiKeyService.create(UUID appId, String name): CreatedApiKey(UUID id, String plaintext)` — format `hr_live_<prefix 8 base62>_<secret 32 base62>`; stores SHA-256(secret); `authenticate(String presented): Optional<UUID /*appId*/>` — lookup by prefix, constant-time compare (`MessageDigest.isEqual`), rejects revoked, updates `last_used_at` at most once a minute.
- Produces: `TenantContext.currentAppId(): UUID` (from the `Authentication` principal) — every app-scoped service takes the appId from here.
- Produces: `ApplicationService.create(String name): Application` — publishes a Spring `ApplicationCreated(UUID appId)` event (Task 4 listens to create system event types).
- Routes: `/v1/admin/**` require `Authorization: Bearer <HOOKRELAY_ADMIN_TOKEN>` (constant-time compare); all other `/v1/**` require `Bearer <api key>`; `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**` open; `/actuator/prometheus` open only on management port (Task 17). Stateless, CSRF off.
- Admin API: `POST/GET /v1/admin/applications`, `GET/PATCH /v1/admin/applications/{id}`, `POST/GET /v1/admin/applications/{id}/api-keys`, `DELETE /v1/admin/applications/{id}/api-keys/{keyId}` (revoke). Key list never returns plaintext or hash.

- [ ] **Step 1: Failing tests:**
  - `ApiKeyServiceTest.plaintextShownOnceAndHashStored`: stored row's `secret_hash` ≠ plaintext bytes; `authenticate(plaintext)` → app id; `authenticate(plaintext + "x")` → empty; revoked → empty.
  - `AuthIT`: no header → 401 problem JSON; API key on `/v1/admin/applications` → 401; admin token on `/v1/endpoints` → 401; disabled application's key → 401.
  - `AdminApiIT.createAppAndKey`: create app → 201 with id; create key → 201 with `key` starting `hr_live_`; list keys → no `key` field.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `Add applications, hashed API keys and two-level auth`.

---

### Task 4: Event types

**Files:**
- Create: `db/migration/V3__event_types.sql`, `eventtype/*`
- Test: `eventtype/EventTypeIT.java`, `eventtype/SchemaValidatorTest.java`

**Interfaces:**
- Table: `event_types(id UUID PK, application_id, name VARCHAR(100), description, json_schema JSON NULL, system BOOL, archived_at NULL, created_at, UNIQUE(application_id,name))`. Name regex `^[a-z0-9]+(\.[a-z0-9_]+)+$`.
- Produces: `EventTypeService.requirePostable(UUID appId, String name): EventType` — throws 422 problem `unknown-event-type` / `archived-event-type` / `reserved-event-type` (for `hookrelay.*`).
- Produces: `SchemaValidator.validate(String schemaJson, String payloadJson): List<String>` (errors; empty = valid). Schema itself validated on create/update (bad schema → 400).
- Produces: `SystemEventTypes.NAMES = List.of("hookrelay.endpoint.disabled","hookrelay.delivery.dead","hookrelay.test")`; listener on `ApplicationCreated` inserts them with `system=true`.
- API: `POST/GET /v1/event-types`, `GET/PATCH /v1/event-types/{name}`, `POST /v1/event-types/{name}/archive`, `.../unarchive`. System types: listed, but PATCH/archive → 409; POST with `hookrelay.` prefix → 422.

- [ ] **Step 1: Failing tests:** create+get+list; duplicate name → 409; new app already lists the 3 system types; creating `hookrelay.foo` → 422; archiving a system type → 409; invalid JSON Schema → 400; `SchemaValidatorTest` valid/invalid payload against `{"type":"object","required":["id"]}`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add event types with schemas and reserved system types`.

---

### Task 5: SSRF guard

**Files:**
- Create: `endpoint/SsrfGuard.java`; extend `HookRelayProperties` with `Ssrf(boolean allowHttp, List<String> allowedCidrs)`
- Test: `endpoint/SsrfGuardTest.java`

**Interfaces:**
- Produces: `SsrfGuard.checkUrl(URI url): void` (scheme/host syntax; throws `SsrfViolation(reason)`), `SsrfGuard.resolveAllowed(String host): List<InetAddress>` (resolves via an injectable `HostResolver` interface — needed for the rebinding test; throws `SsrfViolation` if **any** resolved address is blocked and not in `allowedCidrs`).
- Blocked: loopback, 0.0.0.0/8, 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, 224/4, 240/4, 255.255.255.255, ::, ::1, fc00::/7, fe80::/10, ff00::/8, IPv4-mapped IPv6 of any blocked v4. Non-https rejected unless `allowHttp`. URLs with userinfo rejected.

- [ ] **Step 1: Failing test** `SsrfGuardTest` — parameterized blocked list (each above incl. `169.254.169.254`, `[::ffff:127.0.0.1]`, `http://` when allowHttp=false, `https://user:pw@x.com`) and allowed (`93.184.216.34`, `2606:2800::1`); `allowedCidrs=["172.16.0.0/12"]` lets `172.18.0.5` through but still blocks `10.0.0.1`.
- [ ] **Step 2–4:** FAIL → implement (parse CIDRs once; compare with `InetAddress` byte masks, no new dependency) → PASS. Set `application-test.yml`: `hookrelay.ssrf.allow-http: true`, `allowed-cidrs: ["127.0.0.1/32"]` (WireMock runs on loopback in the test JVM); `prod` sets `allow-http: false` and an empty list.
- [ ] **Step 5:** Commit `Add SSRF guard with dev allowlist`.

---

### Task 6: Secret encryption, signing, verifier

**Files:**
- Create: `crypto/SecretCipher.java`, `crypto/WebhookSigner.java`
- Create: `hookrelay-receiver/src/main/java/dev/hookrelay/receiver/WebhookVerifier.java` (plain Java, no Spring types — the "client helper")
- Test: `crypto/SecretCipherTest.java`, `crypto/WebhookSignerTest.java`, `hookrelay-receiver/.../WebhookVerifierTest.java`

**Interfaces:**
- Produces: `SecretCipher.encrypt(String plain): byte[]` / `decrypt(byte[]): String` — AES-256-GCM, random 12-byte IV prepended, key = base64 `HOOKRELAY_ENCRYPTION_KEY` (must decode to 32 bytes, else startup fails).
- Produces: `WebhookSigner.newSecret(): String` (`whsec_` + base64 of 24 random bytes); `sign(String msgId, long tsSeconds, byte[] body, List<String> secrets): String` → `"v1,<sig1> v1,<sig2>"`; HMAC key = base64-decoded part after `whsec_`; signed content = `msgId + "." + ts + "." + body` (body bytes, UTF-8 for the prefix).
- Produces: `WebhookVerifier(String secret, Clock clock).verify(Map<String,String> headers, byte[] body): void` — throws `WebhookVerificationException` on missing headers, |now−ts| > 5 min, or no matching `v1` signature (constant-time compare).

- [ ] **Step 1: Failing tests:**
  - `WebhookSignerTest.matchesStandardWebhooksVector`: use the official test vector from the standard-webhooks repo (`libraries/javascript/src/index.test.ts` / spec) — fetch it, paste secret/id/timestamp/payload/expected verbatim into the test.
  - two secrets → two space-separated `v1,` entries.
  - `WebhookVerifierTest`: verifies signer output; tampered body → exception; ts 6 min old → exception; header with old+new sig verifies with either secret.
  - `SecretCipherTest`: round-trip; same plaintext twice → different ciphertext; flipped byte → exception.
- [ ] **Step 2–4:** FAIL → implement → PASS (`./mvnw -pl hookrelay-server,hookrelay-receiver test`).
- [ ] **Step 5:** Commit `Add Standard Webhooks signing, verifier and secret encryption`.

---

### Task 7: Endpoints API

**Files:**
- Create: `db/migration/V4__endpoints.sql`, `endpoint/Endpoint.java`, `endpoint/EndpointSecret.java`, repos, `endpoint/EndpointService.java`, `endpoint/EndpointController.java`
- Test: `endpoint/EndpointIT.java`

**Interfaces:**
- Tables: `endpoints(id UUID PK, application_id, url VARCHAR(2048), description, status ENUM('ACTIVE','PAUSED','DISABLED'), rate_limit_per_sec INT NULL, circuit_state ENUM('CLOSED','OPEN','HALF_OPEN') DEFAULT 'CLOSED', consecutive_failures INT, circuit_open_until NULL, circuit_level TINYINT, last_success_at NULL, first_failure_at NULL, deleted_at NULL, created_at, updated_at, version BIGINT)`; `endpoint_event_types(endpoint_id, event_type_name, PK both)`; `endpoint_secrets(id, endpoint_id, secret_enc VARBINARY, created_at, expires_at NULL)`.
- Produces: `EndpointService.activeSecrets(UUID endpointId, Instant now): List<String>` (decrypted, unexpired, newest first); `subscribers(UUID appId, String eventType): List<Endpoint>` — non-deleted, status ACTIVE or PAUSED, and (explicit subscription to the type, or empty list **and** type not `hookrelay.*`).
- API: `POST/GET /v1/endpoints`, `GET/PATCH/DELETE /v1/endpoints/{id}`, `POST .../pause`, `POST .../resume` (Task 14 adds re-enqueue), `POST .../rotate-secret` (old secret gets `expires_at = now+24h`), `GET .../secret` (current secret). Create returns the secret once. `DELETE` is soft (`deleted_at`). URL checked with `SsrfGuard.checkUrl` + `resolveAllowed` → 422 `ssrf-blocked`. Subscribed types must exist in the app (system types allowed) → else 422.

- [ ] **Step 1: Failing tests:** create → 201 with `secret` starting `whsec_`, GET omits it; `GET /secret` returns it; rotate → `activeSecrets` has 2 entries, and after `MutableClock.advance(25h)` has 1; `http://169.254.169.254/` and `http://10.0.0.1/` → 422 `ssrf-blocked`; unknown event type → 422; app B GET app A's endpoint → 404; `subscribers` excludes system types for empty-list endpoints and includes them when listed.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add endpoints with encrypted rotating secrets`.

---

### Task 8: Event ingest, idempotency, fan-out

**Files:**
- Create: `db/migration/V5__events_deliveries_outbox.sql`, `event/*`, `delivery/Delivery.java`, `delivery/DeliveryRepository.java`, `outbox/OutboxMessage.java`, `outbox/OutboxRepository.java`
- Test: `event/IngestIT.java`

**Interfaces:**
- Tables:
  - `events(id UUID PK, application_id, event_type, payload JSON NULL, payload_purged_at NULL, created_at, INDEX(application_id, created_at, id))`
  - `idempotency_keys(application_id, idem_key VARCHAR(255), body_sha256 BINARY(32), event_id, created_at, PK(application_id, idem_key))`
  - `deliveries(id UUID PK, application_id, event_id, endpoint_id, status ENUM('PENDING','FAILED_RETRYING','SUCCEEDED','DEAD'), dead_reason VARCHAR(40) NULL, attempt_count INT, next_attempt_at, deadline_at, dispatch_token UUID, parked_at NULL, last_error VARCHAR(500) NULL, created_at, updated_at, INDEX(endpoint_id,status), INDEX(status,next_attempt_at), INDEX(application_id,created_at,id))`
  - `outbox(id BIGINT AUTO_INCREMENT PK, delivery_id, dispatch_token, delay_tier VARCHAR(8) NULL, created_at, published_at NULL, INDEX(published_at, id))`
- Produces: `IngestService.ingest(UUID appId, String type, byte[] body, @Nullable String idemKey): IngestResult(UUID eventId, int deliveryCount, boolean replayed)` — one `@Transactional`.
- Produces: `DeliveryDispatcher.enqueue(Delivery d, @Nullable DelayTier tier)` — sets a fresh `dispatch_token` on the delivery and inserts an outbox row with it, in the caller's transaction. **Every** path that wants a delivery processed (ingest, retry, defer, replay, resume, sweeper) goes through this. (Retry/defer may bypass the outbox and publish directly — see Task 11 — but must still rotate the token.)
- API: `POST /v1/events` body `{"eventType":"order.created","payload":{...}}`. The stored payload is the exact substring of the `payload` value — parse the request with Jackson streaming to capture the raw bytes of `payload`, don't re-serialize. Payload must be a JSON object. > 262,144 bytes → 413. Response `202 {"id","deliveryCount"}`; idempotent replay returns the original body with header `Idempotent-Replayed: true`.
- New delivery: `status=PENDING, attempt_count=0, next_attempt_at=now, deadline_at=now+3d`.

- [ ] **Step 1: Failing tests:** 2 subscribed endpoints + 1 unsubscribed → `deliveryCount=2`, 2 deliveries + 2 outbox rows; same `Idempotency-Key` + same body → same id, no new rows; same key + different body → 422; unknown type → 422; schema mismatch → 422 with errors; payload `[1,2]` → 400; 262,145-byte payload → 413; payload `{"b":1,  "a":"ü"}` stored exactly as sent; failure mid-transaction (inject failing repo via `@MockitoSpyBean`) leaves zero rows.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add transactional event ingest with idempotency and outbox`.

---

### Task 9: RabbitMQ topology and outbox relay

**Files:**
- Create: `config/RabbitTopology.java`, `delivery/DelayTier.java`, `outbox/OutboxRelay.java`
- Test: `delivery/DelayTierTest.java`, `outbox/OutboxRelayIT.java`

**Interfaces:**
- Topology (all durable): direct exchange `hookrelay.work` → quorum queue `deliveries.work` (rk `work`); direct exchange `hookrelay.delay` → one classic queue per tier `deliveries.delay.<tier>` with `x-message-ttl` = tier ms, `x-dead-letter-exchange=hookrelay.work`, `x-dead-letter-routing-key=work`; direct exchange `hookrelay.dead` → quorum queue `deliveries.dead`.
- Produces: `enum DelayTier { S1, S5, S30, M5, M30, H1, H2, H5, H10 }` with `duration()`, `queueName()`, `static DelayTier atLeast(Duration d)` (smallest tier ≥ d; > 10h → H10).
- Message: JSON `{"deliveryId":"…","dispatchToken":"…"}`, persistent, `message-id` = dispatch token.
- Produces: `OutboxRelay.relayBatch(): int` — in a tx: `SELECT … FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED` (native query), publish each (to `hookrelay.work`, or `hookrelay.delay` with rk = tier when `delay_tier` set) with publisher confirms (`RabbitTemplate` correlated confirms, wait ≤ 5s), mark confirmed rows published; unconfirmed rows stay. Scheduled every 200 ms (`fixedDelay`), loops while a batch was full.

- [ ] **Step 1: Failing tests:**
  - `DelayTierTest`: `atLeast(3s)=S5`, `atLeast(5m)=M5`, `atLeast(6m)=M30`, `atLeast(20h)=H10`.
  - `OutboxRelayIT.publishesAndMarks`: insert 3 outbox rows → messages appear on `deliveries.work` (read via `RabbitTemplate.receive`) and rows have `published_at`.
  - `OutboxRelayIT.brokerDownThenUp`: stop the Rabbit container's connection (use `RabbitMQContainer` pause via `DockerClient.pauseContainerCmd`), ingest, assert rows unpublished, unpause, await published.
  - `OutboxRelayIT.twoRelaysNoDuplicates`: 500 rows, run `relayBatch` from 4 threads concurrently until drained → exactly 500 messages received.
  - Delay path: row with `delay_tier=S1` arrives on `deliveries.work` after ≥1s.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add RabbitMQ topology with delay tiers and outbox relay`.

---

### Task 10: Webhook HTTP sender

**Files:**
- Create: `delivery/WebhookSender.java`, `delivery/SendResult.java`
- Test: `delivery/WebhookSenderTest.java` (WireMock, no Spring context)

**Interfaces:**
- Produces: `WebhookSender.send(URI url, Map<String,String> headers, byte[] body): SendResult` — JDK `HttpClient` with `Redirect.NEVER`, connect timeout 5s, request timeout 15s (both constructor args so tests can shrink them). IP pinning is **not** done (JDK HttpClient can't pin an IP while keeping TLS hostname checks); the worker re-runs `SsrfGuard.resolveAllowed` immediately before each send, and ADR 0007 documents the remaining small DNS time-of-check/time-of-use window.
- `record SendResult(Outcome outcome, Integer status, long durationMs, String bodySnippet, String error)`; `enum Outcome { SUCCESS, HTTP_ERROR, REDIRECT, TIMEOUT, CONNECTION_ERROR, TLS_ERROR }`. 2xx = SUCCESS. Body read through `BodyHandlers.ofInputStream()` capped at 4,096 bytes, rest discarded; snippet decoded UTF-8 with replacement.
- Default headers added by the sender: `content-type: application/json`, `user-agent: HookRelay/1.0`.

- [ ] **Step 1: Failing tests:** 200 → SUCCESS; 500 → HTTP_ERROR status 500; **302 → REDIRECT, Location not requested** (Review Focus 1); delay 16s → TIMEOUT within 16s (Review Focus 2, use a test-sized timeout constructor arg); 10 MB body → snippet length ≤ 4096 and heap untouched (Review Focus 2); closed port → CONNECTION_ERROR; headers and exact body bytes arrive (WireMock `verify` with `binaryEqualTo`).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add webhook HTTP sender with timeouts, no redirects and capped reads`.

---

### Task 11: Delivery worker, retries, DLQ

**Files:**
- Modify: `outbox/OutboxRelay.java` — rows with `target='DEAD'` publish to `hookrelay.dead` instead of the work/delay exchanges.
- Create: `db/migration/V6__attempts.sql` (also adds `outbox.target ENUM('WORK','DEAD') DEFAULT 'WORK'`), `delivery/Attempt.java`, `delivery/AttemptRepository.java`, `delivery/RetryPolicy.java`, `delivery/DeliveryWorker.java`, `delivery/DeliveryStateService.java`; extend `HookRelayProperties.Delivery(List<Duration> retrySchedule, Duration window, Duration sendTimeout, int consumerConcurrency, int prefetch)`
- Create: `application-demo.yml` with `hookrelay.delivery.retry-schedule: 1s,5s,5s,30s,30s,30s,30s`
- Test: `delivery/RetryPolicyTest.java`, `delivery/DeliveryFlowIT.java`

**Interfaces:**
- Table: `attempts(id UUID PK, delivery_id, endpoint_id, attempt_no INT, started_at, duration_ms, response_status INT NULL, response_snippet TEXT NULL, outcome VARCHAR(20), error VARCHAR(500) NULL, INDEX(delivery_id), INDEX(endpoint_id, started_at))`.
- Produces: `RetryPolicy.nextDelay(int attemptsSoFar): Optional<Duration>` — empty when schedule exhausted.
- Produces: `DeliveryStateService` (all `@Transactional`, each a short tx):
  - `claim(UUID deliveryId, UUID token): Optional<Claim>` — `SELECT … FOR UPDATE`; returns empty (→ ack & drop) if token ≠ `dispatch_token`, status final, or deleted; if now > `deadline_at` → mark DEAD `expired` and return empty. On success rotates the token, sets `next_attempt_at = now + sendTimeout + 1 min` (sweeper safety), returns `Claim(delivery, endpoint, event payload bytes, secrets)`.
  - `recordResult(Claim, SendResult)` — inserts Attempt, `attempt_count++`; SUCCESS → SUCCEEDED; else next delay via `RetryPolicy` → `FAILED_RETRYING` + `DeliveryDispatcher.enqueue(d, DelayTier.atLeast(delay))`; none → `markDead(d, "retries_exhausted")`.
  - `defer(Claim, Duration wait, String why)` — no attempt, no count change; enqueue at `DelayTier.atLeast(wait)`.
  - `park(Claim)` — sets `parked_at`, no enqueue.
  - `markDead(Delivery, String reason)` — status DEAD, reason, and an outbox row with `target='DEAD'` (published to `hookrelay.dead` by the relay) so it is transactional.
- Produces: `DeliveryWorker` — `@RabbitListener(queues="deliveries.work", concurrency=…)`, manual ack. Order: claim → endpoint PAUSED/DISABLED → park → circuit (Task 12 hook, no-op now) → rate limit (Task 12 hook) → `SsrfGuard.resolveAllowed` (violation = failed attempt `SSRF_BLOCKED`) → sign with `WebhookSigner` (`webhook-id` = event id, timestamp = now seconds, all active secrets) → `WebhookSender.send` → `recordResult` → ack. Any unexpected exception: log with MDC ids, nack **without** requeue (sweeper recovers; prevents poison-message loops).
- MDC keys `application_id`, `event_id`, `delivery_id` set for the whole handling.

- [ ] **Step 1: Failing tests:**
  - `RetryPolicyTest`: default schedule → delays for 1..7 match, 8th empty.
  - `DeliveryFlowIT` (demo schedule via `@TestPropertySource`, WireMock receiver on a host-reachable port, test profile allowlists the Docker host CIDR + loopback):
    - `happyPath`: ingest → WireMock receives once with valid signature (verify with `WebhookVerifier`) → delivery SUCCEEDED, 1 attempt.
    - `failThenSucceed`: WireMock scenario 500, 500, 200 → SUCCEEDED, 3 attempts, attempt 2→3 gap ≥ 5s.
    - `exhaustsToDead`: always 500 → DEAD `retries_exhausted`, 8 attempts, a message on `deliveries.dead` (before Task 13 adds a consumer, assert via `RabbitTemplate.receive`).
    - `duplicateMessageSentOnce`: publish the same work message twice manually → 1 HTTP call.
    - `pausedEndpointParks`: pause endpoint, ingest → no HTTP call, `parked_at` set.
    - `deadlineExpires`: `MutableClock.advance(3d+1s)` before delivery processed → DEAD `expired`.
    - `exactBytesAndUnicode` (Review Focus 5): payload `{"z":1,  "a":"héllo ☃"}` → WireMock body equals exactly those bytes and signature verifies.
    - `dnsRebindingBlocked` (Review Focus 3): endpoint registered with a hostname the test `HostResolver` maps to a public IP, then remap to `127.0.0.2` (not allowlisted) → attempt outcome `SSRF_BLOCKED`, no HTTP call.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add delivery worker with retry tiers, deadlines and DLQ`.

---

### Task 12: Circuit breaker and rate limits

**Files:**
- Create: `delivery/CircuitBreaker.java`, `delivery/OutboundRateLimiter.java`, `security/InboundRateLimiter.java`, `db/migration/V7__bucket4j.sql` (Bucket4j MySQL-compatible table, per its docs); extend `HookRelayProperties.RateLimit(int inboundPerSecond, int inboundBurst)`
- Test: `delivery/CircuitBreakerTest.java`, `delivery/CircuitAndRateLimitIT.java`, `security/InboundRateLimitIT.java`

**Interfaces:**
- Produces: `CircuitBreaker` — pure state transitions on the `Endpoint` entity, called inside `DeliveryStateService` txs (row already locked):
  - `admit(Endpoint e, Instant now): Admission` → `ALLOW`, `ALLOW_PROBE` (OPEN past `circuit_open_until` → HALF_OPEN; only one probe: set `circuit_open_until = now + sendTimeout`), or `DEFER(Duration remaining)`.
  - `onSuccess(e, now)`: CLOSED, failures 0, level 0, `last_success_at=now`, `first_failure_at=null`.
  - `onFailure(e, now)`: failures++, set `first_failure_at` if null; HALF_OPEN failure or failures ≥ 10 → OPEN with cooldown by level `[5m, 30m, 1h]` (level capped at 2, incremented on each re-open).
- Wire into `DeliveryWorker`: `DEFER(d)` → `defer(claim, d, "circuit_open")`.
- Produces: `OutboundRateLimiter.tryConsume(UUID endpointId, int perSecond): Optional<Duration>` (empty = allowed, else wait) — Bucket4j MySQL `SELECT FOR UPDATE` proxy manager over MariaDB; skipped when `rate_limit_per_sec` null. Worker: wait → `defer(claim, wait, "rate_limited")`.
- Produces: `InboundRateLimiter` — servlet filter after auth, in-memory Bucket4j per API key id (Caffeine-free: `ConcurrentHashMap`, `ponytail:` comment — unbounded map is bounded by number of keys), 429 problem + `Retry-After` seconds.

- [ ] **Step 1: Failing tests:**
  - `CircuitBreakerTest`: 9 failures CLOSED, 10th OPEN with `open_until=now+5m`; admit before → DEFER(remaining); after → ALLOW_PROBE and second admit → DEFER; probe success → CLOSED; probe failure → OPEN 30m, next 1h, next 1h.
  - `CircuitAndRateLimitIT.circuitOpensAndDefers` (threshold 3 via property): receiver 500s → after 3 attempts no more HTTP until cooldown; receiver fixed → probe → SUCCEEDED; deferrals did not increase `attempt_count`.
  - `CircuitAndRateLimitIT.rateLimitHolds`: endpoint `rate_limit_per_sec=2`, 10 events, **two** listener containers (concurrency 4) → WireMock request timestamps never exceed 2 in any 1s window (allow 1 burst token), all 10 SUCCEEDED.
  - `InboundRateLimitIT`: limit 5/s → 6th request in the same second 429 with `Retry-After`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add per-endpoint circuit breaker and rate limits`.

---

### Task 13: Notifications, DLQ consumer, auto-disable, test send

**Files:**
- Create: `notify/SystemEvents.java`, `notify/DeadLetterConsumer.java`, `notify/EndpointDisabler.java`
- Test: `notify/NotificationsIT.java`

**Interfaces:**
- Produces: `SystemEvents.emit(UUID appId, String type, Map<String,Object> data)` — calls `IngestService` internal path that bypasses the reserved-prefix check (`ingestSystem(...)`); payload shapes:
  - `hookrelay.delivery.dead`: `{"deliveryId","eventId","endpointId","eventType","reason","attempts"}`
  - `hookrelay.endpoint.disabled`: `{"endpointId","url","reason":"no_success_3_days","firstFailureAt"}`
  - `hookrelay.test`: `{"message":"Test event from HookRelay","endpointId"}`
- Produces: `DeadLetterConsumer` — `@RabbitListener(queues="deliveries.dead")`: increments `hookrelay.deliveries.dead` counter (tag reason), and emits `delivery.dead` **unless the dead delivery's event type starts with `hookrelay.`** (loop guard).
- Produces: `EndpointDisabler.check(Endpoint e, Instant now)` — called from `CircuitBreaker.onFailure` path: if `first_failure_at` ≤ now − 3d and no success since → status DISABLED, all its PENDING/FAILED_RETRYING deliveries → `markDead(…, "endpoint_disabled")`, emit `endpoint.disabled`.
- API: `POST /v1/endpoints/{id}/test` → creates a `hookrelay.test` event targeted **only** at that endpoint (delivery created directly, not via `subscribers`), returns `202 {"eventId","deliveryId"}`.

- [ ] **Step 1: Failing tests:** dead delivery of `order.created` → an endpoint subscribed to `hookrelay.delivery.dead` receives a payload with matching `deliveryId`; that notification endpoint itself failing to DEAD produces **no** further `delivery.dead` event (count events of that type = 1); endpoint failing with clock advanced 3d → DISABLED, pending deliveries DEAD `endpoint_disabled`, `endpoint.disabled` delivered; `/test` → only that endpoint gets a request, with `webhook-id` = returned eventId; an endpoint subscribed to all types (empty list) gets none of these.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add system notifications, DLQ consumer and endpoint auto-disable`.

---

### Task 14: Replay, resume, endpoint deletion

**Files:**
- Create: `delivery/ReplayService.java`; modify `endpoint/EndpointService.java` (resume, delete), `endpoint/EndpointController.java`, add `delivery/DeliveryController.java` replay routes
- Test: `delivery/ReplayIT.java`

**Interfaces:**
- Produces: `ReplayService.replay(UUID appId, UUID deliveryId): Delivery` — allowed from any final state (SUCCEEDED, DEAD); 409 if not final; 410 `payload-purged` if event payload purged; resets `status=PENDING, attempt_count=0, dead_reason=null, deadline_at=now+window, parked_at=null` and `DeliveryDispatcher.enqueue`. Attempts history kept (attempt_no continues increasing).
- Produces: `ReplayService.replayEndpoint(UUID appId, UUID endpointId, Instant since): BulkReplayResult(int replayed, int skippedPurged)` — DEAD deliveries created ≥ since, processed in batches of 500.
- `EndpointService.resume`: status ACTIVE, circuit CLOSED / failures 0 / level 0, then every parked delivery for it (`parked_at` not null, non-final) → clear `parked_at` + enqueue. Works from PAUSED and DISABLED.
- `EndpointService.delete`: soft delete + all non-final deliveries → `markDead(…, "endpoint_deleted")`. Worker `claim` on a deleted endpoint's delivery → treat as DEAD `endpoint_deleted` (in case of race).
- API: `POST /v1/deliveries/{id}/replay`, `POST /v1/endpoints/{id}/replay` body `{"since":"2026-11-10T00:00:00Z"}`.

- [ ] **Step 1: Failing tests:** DEAD → replay with receiver now healthy → SUCCEEDED, same `webhook-id` as before; replaying a PENDING delivery → 409; purged payload → 410; bulk replay of 3 DEAD (1 purged) → `{replayed:2, skippedPurged:1}`; paused endpoint with 2 parked deliveries → resume → both delivered; **delete endpoint while a delivery sits in the 5m delay queue (Review Focus 4)** → delivery DEAD `endpoint_deleted`, when its message returns the worker drops it without error and without HTTP.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add single and bulk replay, resume re-enqueue and safe endpoint deletion`.

---

### Task 15: Query APIs, stats, tenant isolation

**Files:**
- Create/modify: `event/EventController.java` (list/get), `delivery/DeliveryController.java` (list/get/attempts), `stats/EndpointStatsService.java`, `endpoint/EndpointController.java` (stats route)
- Test: `query/QueryApiIT.java`, `query/TenantIsolationIT.java`

**Interfaces:**
- `GET /v1/events?eventType=&from=&to=&cursor=&limit=`; `GET /v1/events/{id}` (includes payload, or `payloadPurged:true`) and `GET /v1/events/{id}/deliveries`.
- `GET /v1/deliveries?status=&endpointId=&eventId=&from=&to=&cursor=&limit=`; `GET /v1/deliveries/{id}`; `GET /v1/deliveries/{id}/attempts` (ordered by attempt_no).
- `GET /v1/endpoints/{id}/stats` → `{"window":"24h","attempts","successes","successRate","p50Ms","p95Ms","deliveriesByStatus":{...},"circuitState","lastSuccessAt"}` computed from `attempts` where `started_at ≥ now−24h` (percentiles in SQL via `PERCENTILE_CONT … OVER ()` or in Java over the window — builder's call; add index already present).
- All list queries filter by `application_id` first; cursor uses `(created_at, id)` keyset.

- [ ] **Step 1: Failing tests:** filters (status=DEAD only returns dead), pagination over 120 deliveries with limit 50 returns 50/50/20 with no duplicates and stable order; invalid status value → 400; stats on known fixture (attempt durations 10..100ms) → p50 ≈ 55, successRate matches; `TenantIsolationIT`: app B gets 404 on every app-A resource route (events, event deliveries, deliveries, attempts, replay, endpoint get/patch/delete/pause/resume/rotate/secret/test/stats/replay, event type get/patch/archive) — table-driven over the route list.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add query APIs, endpoint stats and tenant isolation tests`.

---

### Task 16: Scheduled jobs

**Files:**
- Create: `jobs/ShedLockConfig.java`, `jobs/RetentionJob.java`, `jobs/ExpirySweeper.java`, `jobs/StuckDeliverySweeper.java`, `db/migration/V8__shedlock.sql`; extend `HookRelayProperties.Retention(Duration payloads, Duration attempts, Duration idempotencyKeys, Duration publishedOutbox)`
- Test: `jobs/JobsIT.java`

**Interfaces:**
- All jobs `@SchedulerLock` (ShedLock JDBC provider on MariaDB) and delete/update in batches of 1,000 until none left.
- `RetentionJob.run()` — cron `0 30 2 * * *`: purge payloads (set NULL + `payload_purged_at`), delete attempts, idempotency keys, published outbox rows per retention config.
- `ExpirySweeper.run()` — every 1 min: parked deliveries past `deadline_at` → `markDead(…, "expired")`.
- `StuckDeliverySweeper.run()` — every 1 min: non-final, non-parked deliveries with `next_attempt_at < now − 15m` and no unpublished outbox row → `DeliveryDispatcher.enqueue(d, null)`; counter `hookrelay.deliveries.recovered`. (Requires every enqueue path to set `next_attempt_at` to when the message is expected to arrive — add that to `DeliveryDispatcher.enqueue` now: `next_attempt_at = now + tier duration`.)

- [ ] **Step 1: Failing tests:** advance clock 31d → payload null, `GET /v1/events/{id}` shows `payloadPurged:true`; 91d → attempts gone; parked delivery past deadline → DEAD expired; a delivery whose work message was purged from RabbitMQ (`purgeQueue`) is redelivered after clock +16m and sweeper run; two concurrent `RetentionJob.run()` calls → only one executes (ShedLock row).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit `Add retention, expiry and stuck-delivery sweeper jobs`.

---

### Task 17: Observability, profiles, graceful shutdown, OpenAPI

**Files:**
- Modify: `application.yml`, create `application-dev.yml`, `application-prod.yml`, `logback-spring.xml`
- Create: `ops/prometheus.yml`, `ops/grafana/provisioning/{datasources,dashboards}/*.yml`, `ops/grafana/dashboards/hookrelay.json`
- Test: `ops/MetricsIT.java`, `ops/OpenApiIT.java`

**Interfaces:**
- Metrics (Micrometer, prefix `hookrelay.`): `events.ingested` (tag app-agnostic: `event_type` **not** tagged — cardinality), `deliveries.completed{outcome=succeeded|dead}`, `attempts{outcome}`, `attempt.duration` timer with histogram, `outbox.backlog` gauge (count unpublished), `outbox.lag` gauge (age of oldest unpublished), `endpoints.circuit{state}` gauge, `deliveries.dead{reason}`, `deliveries.recovered`. RabbitMQ queue depths come from the Rabbit Prometheus plugin, not the app.
- Management on port 8081 (`management.server.port`), exposing `health,info,prometheus`; health groups `liveness` (ping) and `readiness` (db, rabbit).
- Logs: JSON (Spring Boot structured logging `logging.structured.format.console=ecs`) in `prod`/`demo`, plain in `dev`; MDC keys appear as fields.
- Graceful: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`, listener container `shutdownTimeout` 20s.
- springdoc: `/v3/api-docs`, `/swagger-ui.html`, bearer security scheme declared.
- Grafana dashboard panels: ingest rate, delivery success vs dead, attempt latency p50/p95, outbox backlog/lag, circuit states, queue depths.

- [ ] **Step 1: Failing tests:** after one successful delivery `/actuator/prometheus` on the management port contains `hookrelay_attempt_duration_seconds_bucket` and `hookrelay_deliveries_completed_total{outcome="succeeded"} 1.0`; `/v3/api-docs` lists `/v1/events` and a `bearerAuth` scheme; `/actuator/health/readiness` UP.
- [ ] **Step 2–4:** FAIL → implement → PASS. Manually: `jq . ops/grafana/dashboards/hookrelay.json` parses.
- [ ] **Step 5:** Commit `Add metrics, JSON logs, health groups, OpenAPI and Grafana dashboard`.

---

### Task 18: Receiver app, packaging, full Compose, demo, CI

**Files:**
- Create: `hookrelay-receiver/src/main/java/dev/hookrelay/receiver/{ReceiverApplication,ReceiverController,ChaosSettings}.java`, `hookrelay-receiver/Dockerfile`, `hookrelay-server/Dockerfile`, `docker-compose.yml`, `.env.example`, `scripts/demo.sh`, `.github/workflows/ci.yml`
- Test: `hookrelay-receiver/.../ReceiverControllerTest.java`

**Interfaces:**
- Receiver: `POST /webhooks` verifies with `WebhookVerifier` (secret from env `WEBHOOK_SECRET` or set via `PUT /chaos/secret`), dedupes on `webhook-id` (in-memory set), logs one line per received event; `PUT /chaos` body `{"failureRate":0.0-1.0,"forceStatus":503|null,"addedLatencyMs":0}`; `GET /received` lists last 100 `{webhookId, eventType, verified, duplicate, at}`.
- Dockerfiles: multi-stage (`eclipse-temurin:21-jdk` build with `./mvnw -pl <module> -am package -DskipTests`, run on `eclipse-temurin:21-jre`), non-root user, `HEALTHCHECK` on management port.
- Compose: mariadb, rabbitmq (with `rabbitmq_prometheus` enabled), `server-1` and `server-2` (profile `demo`, ports 8080/8090, allowHttp true, allowedCidrs = compose subnet), receiver, prometheus, grafana (anonymous viewer, provisioned). Secrets from `.env` (`.env.example` committed, `.env` ignored).
- `scripts/demo.sh` (bash + curl + jq, `set -euo pipefail`): create app + key via admin API → create event types → create endpoint pointing at receiver and configure receiver secret → send 5 events → show received → `PUT /chaos forceStatus=503` → send 3 events → poll and print attempts as they retry → show DEAD list → clear chaos → bulk replay → show SUCCEEDED. Prints Grafana URL at the end.
- CI: on push/PR — `actions/setup-java` temurin 21 with Maven cache, `./mvnw -B verify` (Testcontainers works on `ubuntu-latest`), then `docker build` both images (no push).

- [ ] **Step 1: Failing test** `ReceiverControllerTest`: valid signature → 200 and `verified:true`; bad signature → 401; same `webhook-id` twice → second marked `duplicate:true`; `forceStatus=503` → 503.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** `cp .env.example .env && docker compose up -d --build`; wait for health; `./scripts/demo.sh` → completes with exit 0 and final SUCCEEDED listing. `docker compose down -v`.
- [ ] **Step 6:** Commit `Add sample receiver, Docker images, full compose stack, demo script and CI`.

---

### Task 19: Load test, README, ADRs

**Files:**
- Create: `loadtest/ingest.js`, `loadtest/README.md`, `README.md`, `docs/adr/0001-rabbitmq-over-kafka.md`, `0002-transactional-outbox.md`, `0003-database-is-source-of-truth.md`, `0004-at-least-once-and-webhook-id.md`, `0005-sha256-for-api-keys.md`, `0006-fixed-retry-tiers-no-jitter.md`, `0007-ssrf-checked-at-send-time.md`, `0008-rate-limits-without-redis.md`

**Interfaces:**
- k6 script: setup creates app/key/endpoint (receiver with 0 latency), then constant-arrival-rate ingest for 60s at a rate given by env `RATE`; reports p95 ingest latency, error rate. A second phase measures end-to-end delivery latency by polling `GET /v1/deliveries?status=SUCCEEDED` counts over time (or receiver `/received` timestamps vs event creation).
- ADR format: Context / Decision / Consequences / Alternatives, ≤ 1 page each, each states the known ceiling (e.g. 0008: Bucket4j row lock per outbound send caps per-endpoint throughput; inbound limit is per instance).
- README sections: one-paragraph pitch; architecture Mermaid diagram (ingest → MariaDB (events, deliveries, outbox) → relay → `hookrelay.work` → workers → receivers; delay tiers loop; DLQ → notifications); features; quick start (`docker compose up`, `scripts/demo.sh`); API overview table with link to Swagger; delivery semantics (at-least-once, no ordering, retry schedule table, circuit breaker, deadlines); security (auth, signing, SSRF, encryption); observability (screenshot of the Grafana dashboard saved to `docs/img/`); **measured** results table (hardware, rate, p95, error rate) copied from an actual k6 run; "How I'd scale it" (Kafka partitioned by endpoint, Redis rate limits, per-endpoint queues, payload storage in S3); ADR index.

- [ ] **Step 1:** Write `loadtest/ingest.js`; run against the Compose stack: `k6 run -e RATE=200 loadtest/ingest.js` (install with `brew install k6` if missing). Increase RATE until error rate > 1% or p95 > 500ms; record the last passing rate.
- [ ] **Step 2:** Write ADRs and README using only measured numbers from Step 1 and behaviour proven by tests.
- [ ] **Step 3:** Verify every README command works on a clean checkout: `git clone . /tmp/hr-check && cd /tmp/hr-check && cp .env.example .env && docker compose up -d --build && ./scripts/demo.sh`.
- [ ] **Step 4:** `./mvnw verify` from root → BUILD SUCCESS, all tests green.
- [ ] **Step 5:** Commit `Add load test, README with measured results, and ADRs`.
