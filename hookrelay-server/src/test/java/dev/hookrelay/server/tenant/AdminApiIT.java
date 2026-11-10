package dev.hookrelay.server.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AdminApiIT extends IntegrationTest {

    @Test
    void createAppAndKeyThenListWithoutSecrets() {
        ResponseEntity<JsonNode> app = admin().post().uri("/v1/admin/applications")
                .body(Map.of("name", "Acme Shop")).retrieve().toEntity(JsonNode.class);
        assertThat(app.getStatusCode().value()).isEqualTo(201);
        String appId = app.getBody().at("/id").asString();
        assertThat(app.getBody().at("/name").asString()).isEqualTo("Acme Shop");
        assertThat(app.getBody().at("/status").asString()).isEqualTo("ACTIVE");

        ResponseEntity<JsonNode> key = admin().post().uri("/v1/admin/applications/{id}/api-keys", appId)
                .body(Map.of("name", "prod")).retrieve().toEntity(JsonNode.class);
        assertThat(key.getStatusCode().value()).isEqualTo(201);
        assertThat(key.getBody().at("/key").asString()).startsWith("hr_live_");

        JsonNode list = admin().get().uri("/v1/admin/applications/{id}/api-keys", appId)
                .retrieve().body(JsonNode.class);
        assertThat(list.at("/data").size()).isEqualTo(1);
        assertThat(list.at("/data/0/key").isMissingNode()).isTrue();
        assertThat(list.at("/data/0/prefix").asString()).hasSize(8);
    }

    @Test
    void blankNameIsValidationError() {
        ResponseEntity<JsonNode> res = admin().post().uri("/v1/admin/applications")
                .body(Map.of("name", " ")).retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownApplicationIs404() {
        ResponseEntity<JsonNode> res = admin().get().uri("/v1/admin/applications/{id}",
                "01900000-0000-7000-8000-000000000000").retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void listApplicationsPaginates() {
        for (int i = 0; i < 3; i++) {
            admin().post().uri("/v1/admin/applications").body(Map.of("name", "p" + i)).retrieve().toBodilessEntity();
        }
        JsonNode page = admin().get().uri("/v1/admin/applications?limit=2").retrieve().body(JsonNode.class);
        assertThat(page.at("/data").size()).isEqualTo(2);
        assertThat(page.at("/nextCursor").asString()).isNotBlank();
    }
}
