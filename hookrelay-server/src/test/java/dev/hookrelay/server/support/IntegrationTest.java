package dev.hookrelay.server.support;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;

/**
 * Base for integration tests. Containers are started once per JVM and shared by every
 * Spring context, so adding test classes doesn't add container start-up time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    @ServiceConnection
    protected static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

    @ServiceConnection
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    static {
        MARIADB.start();
        RABBIT.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    protected static final String ADMIN_TOKEN = "test-admin-token";

    /** HTTP client against the running app that never throws on 4xx/5xx. */
    protected RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    /** Client authenticated with the given bearer token. */
    protected RestClient client(String bearer) {
        return client().mutate().defaultHeader("Authorization", "Bearer " + bearer).build();
    }

    protected RestClient admin() {
        return client(ADMIN_TOKEN);
    }

    /** A fresh application plus an API key for it. */
    protected record Tenant(String appId, String apiKey) { }

    protected Tenant newTenant() {
        JsonNode app = admin().post().uri("/v1/admin/applications")
                .body(Map.of("name", "app-" + System.nanoTime())).retrieve().body(JsonNode.class);
        String appId = app.at("/id").asString();
        JsonNode key = admin().post().uri("/v1/admin/applications/{id}/api-keys", appId)
                .body(Map.of("name", "test")).retrieve().body(JsonNode.class);
        return new Tenant(appId, key.at("/key").asString());
    }
}
