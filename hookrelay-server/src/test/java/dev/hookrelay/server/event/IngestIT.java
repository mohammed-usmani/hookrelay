package dev.hookrelay.server.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class IngestIT extends IntegrationTest {

    static final String URL = "http://127.0.0.1:9/hook";

    @Autowired JdbcTemplate jdbc;

    Tenant tenant;
    RestClient c;

    @BeforeEach
    void setUp() {
        tenant = newTenant();
        c = client(tenant.apiKey());
        c.post().uri("/v1/event-types").body(Map.of("name", "order.created")).retrieve().toBodilessEntity();
        c.post().uri("/v1/event-types").body(Map.of("name", "user.signed_up")).retrieve().toBodilessEntity();
    }

    private void endpoint(List<String> types) {
        c.post().uri("/v1/endpoints").body(Map.of("url", URL, "eventTypes", types)).retrieve().toBodilessEntity();
    }

    private ResponseEntity<JsonNode> post(String rawJson, String idemKey) {
        var req = c.post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON);
        if (idemKey != null) {
            req = req.header("Idempotency-Key", idemKey);
        }
        return req.body(rawJson.getBytes(StandardCharsets.UTF_8)).retrieve().toEntity(JsonNode.class);
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + where, Integer.class, args);
    }

    @Test
    void fansOutToSubscribedEndpointsOnly() {
        endpoint(List.of("order.created"));
        endpoint(List.of());
        endpoint(List.of("user.signed_up"));

        ResponseEntity<JsonNode> res = post("{\"eventType\":\"order.created\",\"payload\":{\"id\":1}}", null);

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        assertThat(res.getBody().at("/deliveryCount").asInt()).isEqualTo(2);
        String eventId = res.getBody().at("/id").asString();
        assertThat(count("deliveries", "event_id = ?", eventId)).isEqualTo(2);
        assertThat(count("outbox o join deliveries d on d.id = o.delivery_id", "d.event_id = ?", eventId))
                .isEqualTo(2);
        assertThat(count("deliveries", "event_id = ? and status = 'PENDING' and deadline_at > created_at", eventId))
                .isEqualTo(2);
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsTheOriginal() {
        endpoint(List.of());
        String body = "{\"eventType\":\"order.created\",\"payload\":{\"id\":1}}";
        ResponseEntity<JsonNode> first = post(body, "k-1");
        ResponseEntity<JsonNode> second = post(body, "k-1");

        assertThat(second.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getBody().at("/id").asString()).isEqualTo(first.getBody().at("/id").asString());
        assertThat(second.getBody().at("/deliveryCount").asInt()).isEqualTo(1);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(count("events", "application_id = ?", tenant.appId())).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIs422() {
        post("{\"eventType\":\"order.created\",\"payload\":{\"id\":1}}", "k-2");
        ResponseEntity<JsonNode> res = post("{\"eventType\":\"order.created\",\"payload\":{\"id\":2}}", "k-2");
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().at("/type").asString()).endsWith("/idempotency-key-reused");
    }

    @Test
    void unknownTypeIs422() {
        assertThat(post("{\"eventType\":\"nope.nope\",\"payload\":{}}", null).getStatusCode().value())
                .isEqualTo(422);
    }

    @Test
    void schemaMismatchIs422WithErrors() {
        c.post().uri("/v1/event-types").body(Map.of("name", "invoice.paid",
                "schema", Map.of("type", "object", "required", List.of("amount")))).retrieve().toBodilessEntity();
        ResponseEntity<JsonNode> res = post("{\"eventType\":\"invoice.paid\",\"payload\":{\"id\":1}}", null);
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().at("/errors").size()).isPositive();
    }

    @Test
    void payloadMustBeAnObject() {
        assertThat(post("{\"eventType\":\"order.created\",\"payload\":[1,2]}", null).getStatusCode().value())
                .isEqualTo(400);
        assertThat(post("{\"eventType\":\"order.created\"}", null).getStatusCode().value()).isEqualTo(400);
        assertThat(post("{\"eventType\":\"order.created\",\"payload\":{\"a\":", null).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void payloadOver256KbIs413() {
        String filler = "x".repeat(262_144);
        ResponseEntity<JsonNode> res = post("{\"eventType\":\"order.created\",\"payload\":{\"f\":\"" + filler + "\"}}",
                null);
        assertThat(res.getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void payloadIsStoredExactlyAsSent() {
        String payload = "{\"b\":1,  \"a\":\"ü ☃\" , \"n\":1.50}";
        String eventId = post("{\"eventType\":\"order.created\",\"payload\":" + payload + "}", null).getBody()
                .at("/id").asString();
        String stored = jdbc.queryForObject("select payload from events where id = ?", String.class, eventId);
        assertThat(stored).isEqualTo(payload);
    }

    @Test
    void systemTypesCannotBePostedInAnyLetterCase() {
        ResponseEntity<JsonNode> res = post("{\"eventType\":\"HOOKRELAY.DELIVERY.DEAD\",\"payload\":{}}", null);
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().at("/type").asString()).endsWith("/reserved-event-type");
    }

    @Test
    void concurrentRequestsWithTheSameKeyBothGetTheOriginal() throws Exception {
        endpoint(List.of());
        for (int round = 0; round < 10; round++) {
            String key = "race-" + round;
            java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<ResponseEntity<JsonNode>> call = () -> {
                go.await();
                return post("{\"eventType\":\"order.created\",\"payload\":{\"r\":1}}", key);
            };
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(call);
                var b = pool.submit(call);
                ResponseEntity<JsonNode> ra = a.get();
                ResponseEntity<JsonNode> rb = b.get();
                assertThat(List.of(ra.getStatusCode().value(), rb.getStatusCode().value())).as("round " + round)
                        .containsOnly(202);
                assertThat(ra.getBody().at("/id").asString()).isEqualTo(rb.getBody().at("/id").asString());
            } finally {
                pool.shutdown();
            }
        }
        assertThat(count("events", "application_id = ?", tenant.appId())).isEqualTo(10);
    }
}
