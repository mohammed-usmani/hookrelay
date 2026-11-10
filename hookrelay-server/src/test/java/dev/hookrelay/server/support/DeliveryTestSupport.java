package dev.hookrelay.server.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Integration tests that deliver to a real HTTP receiver (WireMock). */
public abstract class DeliveryTestSupport extends IntegrationTest {

    protected static final WireMockServer receiver = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected RabbitListenerEndpointRegistry listeners;

    protected Tenant tenant;
    protected RestClient api;

    @BeforeAll
    static void startReceiver() {
        if (!receiver.isRunning()) {
            receiver.start();
        }
    }

    @AfterAll
    static void resetReceiver() {
        receiver.resetAll();
    }

    @BeforeEach
    void freshTenant() {
        receiver.resetAll();
        Workers.start(listeners);
        tenant = newTenant();
        api = client(tenant.apiKey());
        api.post().uri("/v1/event-types").body(Map.of("name", "order.created")).retrieve().toBodilessEntity();
    }

    protected String receiverUrl(String path) {
        return "http://127.0.0.1:" + receiver.port() + path;
    }

    /** Creates an endpoint and returns {id, secret}. */
    protected JsonNode endpoint(String url, List<String> types) {
        return api.post().uri("/v1/endpoints").body(Map.of("url", url, "eventTypes", types))
                .retrieve().body(JsonNode.class);
    }

    protected String send(String rawPayload) {
        String body = "{\"eventType\":\"order.created\",\"payload\":" + rawPayload + "}";
        return api.post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8)).retrieve().body(JsonNode.class).at("/id").asString();
    }

    protected Map<String, Object> deliveryOf(String eventId) {
        return jdbc.queryForMap("select * from deliveries where event_id = ?", eventId);
    }

    protected String status(String eventId) {
        return jdbc.queryForObject("select status from deliveries where event_id = ?", String.class, eventId);
    }

    protected int attempts(String eventId) {
        return jdbc.queryForObject("""
                select count(*) from attempts a join deliveries d on d.id = a.delivery_id
                where d.event_id = ?""", Integer.class, eventId);
    }
}
