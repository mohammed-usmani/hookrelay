package dev.hookrelay.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AuthIT extends IntegrationTest {

    @Test
    void missingCredentialsIs401ProblemJson() {
        ResponseEntity<JsonNode> res = client().get().uri("/v1/admin/applications").retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    void apiKeyCannotUseAdminRoutes() {
        Tenant t = newTenant();
        assertThat(client(t.apiKey()).get().uri("/v1/admin/applications").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void adminTokenCannotUseAppRoutes() {
        assertThat(admin().get().uri("/v1/whatever").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void validApiKeyReachesAppRoutes() {
        Tenant t = newTenant();
        ResponseEntity<JsonNode> res = client(t.apiKey()).get().uri("/v1/nope").retrieve().toEntity(JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    void disabledApplicationsKeyIsRejected() {
        Tenant t = newTenant();
        admin().patch().uri("/v1/admin/applications/{id}", t.appId())
                .body(Map.of("status", "DISABLED")).retrieve().toBodilessEntity();
        assertThat(client(t.apiKey()).get().uri("/v1/nope").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void healthIsOpen() {
        assertThat(client().get().uri("/actuator/health").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
    }
}
