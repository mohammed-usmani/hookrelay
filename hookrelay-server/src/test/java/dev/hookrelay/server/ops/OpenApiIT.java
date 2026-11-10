package dev.hookrelay.server.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OpenApiIT extends IntegrationTest {

    @Test
    void apiDocsDescribeTheApiAndItsAuth() {
        JsonNode docs = client().get().uri("/v3/api-docs").retrieve().body(JsonNode.class);
        assertThat(docs.at("/paths").has("/v1/events")).isTrue();
        assertThat(docs.at("/paths").has("/v1/endpoints/{id}/replay")).isTrue();
        assertThat(docs.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
        assertThat(client().get().uri("/swagger-ui.html").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful()
                || client().get().uri("/swagger-ui/index.html").retrieve().toBodilessEntity().getStatusCode()
                        .is2xxSuccessful()).isTrue();
    }
}
