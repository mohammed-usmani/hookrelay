package dev.hookrelay.server.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import dev.hookrelay.server.event.IngestService;
import dev.hookrelay.server.support.DeliveryTestSupport;
import dev.hookrelay.server.support.Workers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class QueryApiIT extends DeliveryTestSupport {

    @Autowired IngestService ingest;

    private JsonNode get(String uri, Object... vars) {
        return api.get().uri(uri, vars).retrieve().body(JsonNode.class);
    }

    /** The app stores UTC wall-clock times; a plain Timestamp would use the JVM's zone. */
    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private void ingestMany(int n) {
        UUID app = UUID.fromString(tenant.appId());
        for (int i = 0; i < n; i++) {
            ingest.ingest(app, ("{\"eventType\":\"order.created\",\"payload\":{\"n\":" + i + "}}")
                    .getBytes(StandardCharsets.UTF_8), null);
        }
    }

    @Test
    void statusFilterReturnsOnlyMatchingDeliveries() {
        Workers.stop(listeners);
        endpoint(receiverUrl("/q"), List.of());
        String a = send("{}");
        send("{}");
        jdbc.update("update deliveries set status = 'DEAD', dead_reason = 'expired' where event_id = ?", a);

        JsonNode page = get("/v1/deliveries?status=DEAD");

        assertThat(page.at("/data").size()).isEqualTo(1);
        assertThat(page.at("/data/0/eventId").asString()).isEqualTo(a);
        assertThat(page.at("/data/0/deadReason").asString()).isEqualTo("expired");
        assertThat(get("/v1/deliveries?eventId={e}", a).at("/data").size()).isEqualTo(1);
    }

    @Test
    void paginationIsCompleteStableAndDuplicateFree() {
        Workers.stop(listeners);
        endpoint(receiverUrl("/q"), List.of());
        ingestMany(120);

        List<String> ids = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = cursor == null ? get("/v1/deliveries?limit=50")
                    : get("/v1/deliveries?limit=50&cursor={c}", cursor);
            sizes.add(page.at("/data").size());
            page.at("/data").forEach(d -> ids.add(d.at("/id").asString()));
            JsonNode next = page.at("/nextCursor");
            cursor = next.isString() ? next.asString() : null;
        } while (cursor != null && sizes.size() < 10);

        assertThat(sizes).containsExactly(50, 50, 20);
        assertThat(ids).doesNotHaveDuplicates().hasSize(120);
        List<String> ordered = jdbc.queryForList("""
                select id from deliveries where application_id = ? order by created_at desc, id desc""",
                String.class, tenant.appId());
        assertThat(ids).isEqualTo(ordered);
        assertThat(get("/v1/events?limit=200").at("/data").size()).isEqualTo(120);
    }

    @Test
    void invalidFilterValueIsABadRequest() {
        ResponseEntity<JsonNode> res = api.get().uri("/v1/deliveries?status=NOPE").retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void eventDetailShowsThePayloadUntilItIsPurged() {
        Workers.stop(listeners);
        endpoint(receiverUrl("/q"), List.of());
        String eventId = send("{\"total\":42}");

        JsonNode event = get("/v1/events/{id}", eventId);
        assertThat(event.at("/payload/total").asInt()).isEqualTo(42);
        assertThat(event.at("/payloadPurged").asBoolean()).isFalse();
        assertThat(get("/v1/events/{id}/deliveries", eventId).at("/data").size()).isEqualTo(1);
        assertThat(get("/v1/events?eventType=order.created").at("/data").size()).isEqualTo(1);
        assertThat(get("/v1/events?eventType=other.thing").at("/data").size()).isZero();

        jdbc.update("update events set payload_purged_at = now(6) where id = ?", eventId);
        JsonNode purged = get("/v1/events/{id}", eventId);
        assertThat(purged.at("/payloadPurged").asBoolean()).isTrue();
        assertThat(purged.at("/payload").isMissingNode() || purged.at("/payload").isNull()).isTrue();
    }

    @Test
    void attemptsAreListedInOrder() {
        receiver.stubFor(post("/flaky").inScenario("f").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        receiver.stubFor(post("/flaky").inScenario("f").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)));
        endpoint(receiverUrl("/flaky"), List.of());
        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(eventId)));
        String deliveryId = deliveryOf(eventId).get("id").toString();

        JsonNode attempts = get("/v1/deliveries/{id}/attempts", deliveryId);

        assertThat(attempts.at("/data").size()).isEqualTo(2);
        assertThat(attempts.at("/data/0/attemptNo").asInt()).isEqualTo(1);
        assertThat(attempts.at("/data/0/responseStatus").asInt()).isEqualTo(503);
        assertThat(attempts.at("/data/1/outcome").asString()).isEqualTo("SUCCESS");
        assertThat(get("/v1/deliveries/{id}", deliveryId).at("/status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void endpointStatsSummariseTheLast24Hours() {
        Workers.stop(listeners);
        String id = endpoint(receiverUrl("/q"), List.of()).at("/id").asString();
        String eventId = send("{}");
        String deliveryId = deliveryOf(eventId).get("id").toString();
        Instant now = Instant.now(clock);
        for (int i = 1; i <= 10; i++) {
            jdbc.update("""
                    insert into attempts (id, delivery_id, endpoint_id, attempt_no, started_at, duration_ms,
                                          response_status, outcome)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID().toString(), deliveryId, id, i,
                    utc(now.minusSeconds(60)), i * 10, i <= 8 ? 200 : 500, i <= 8 ? "SUCCESS" : "HTTP_ERROR");
        }
        jdbc.update("""
                insert into attempts (id, delivery_id, endpoint_id, attempt_no, started_at, duration_ms, outcome)
                values (?, ?, ?, 11, ?, 9999, 'TIMEOUT')""", UUID.randomUUID().toString(), deliveryId, id,
                utc(now.minus(Duration.ofHours(25))));

        JsonNode stats = get("/v1/endpoints/{id}/stats", id);

        assertThat(stats.at("/window").asString()).isEqualTo("24h");
        assertThat(stats.at("/attempts").asInt()).isEqualTo(10);
        assertThat(stats.at("/successes").asInt()).isEqualTo(8);
        assertThat(stats.at("/successRate").asDouble()).isCloseTo(0.8, within(0.001));
        assertThat(stats.at("/p50Ms").asDouble()).isCloseTo(55, within(0.5));
        assertThat(stats.at("/p95Ms").asDouble()).isCloseTo(95.5, within(0.5));
        assertThat(stats.at("/deliveriesByStatus/PENDING").asInt()).isEqualTo(1);
        assertThat(stats.at("/circuitState").asString()).isEqualTo("CLOSED");
    }

    @Test
    void statsOnAQuietEndpointAreZeroNotErrors() {
        String id = endpoint(receiverUrl("/q"), List.of()).at("/id").asString();
        JsonNode stats = get("/v1/endpoints/{id}/stats", id);
        assertThat(stats.at("/attempts").asInt()).isZero();
        assertThat(stats.at("/successRate").isNull()).isTrue();
        assertThat(stats.at("/p50Ms").isNull()).isTrue();
    }
}
