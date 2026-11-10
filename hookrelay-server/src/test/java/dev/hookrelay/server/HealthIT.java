package dev.hookrelay.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class HealthIT extends IntegrationTest {

    @Test
    void healthReportsDbAndRabbitUp() {
        JsonNode body = client().get().uri("/actuator/health").retrieve().body(JsonNode.class);
        assertThat(body.at("/status").asString()).isEqualTo("UP");
        assertThat(body.at("/components/db/status").asString()).isEqualTo("UP");
        assertThat(body.at("/components/rabbit/status").asString()).isEqualTo("UP");
    }
}
