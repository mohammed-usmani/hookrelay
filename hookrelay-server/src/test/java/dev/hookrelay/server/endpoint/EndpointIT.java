package dev.hookrelay.server.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class EndpointIT extends IntegrationTest {

    static final String URL = "http://127.0.0.1:9/hook";

    @Autowired EndpointService endpoints;

    private ResponseEntity<JsonNode> create(RestClient c, Map<String, Object> body) {
        return c.post().uri("/v1/endpoints").body(body).retrieve().toEntity(JsonNode.class);
    }

    private void eventType(RestClient c, String name) {
        c.post().uri("/v1/event-types").body(Map.of("name", name)).retrieve().toBodilessEntity();
    }

    @Test
    void createShowsSecretOnceAndGetHidesIt() {
        RestClient c = client(newTenant().apiKey());
        ResponseEntity<JsonNode> res = create(c, Map.of("url", URL, "description", "orders"));
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        String secret = res.getBody().at("/secret").asString();
        assertThat(secret).startsWith("whsec_");
        String id = res.getBody().at("/id").asString();

        JsonNode got = c.get().uri("/v1/endpoints/{id}", id).retrieve().body(JsonNode.class);
        assertThat(got.at("/secret").isMissingNode()).isTrue();
        assertThat(got.at("/status").asString()).isEqualTo("ACTIVE");

        assertThat(c.get().uri("/v1/endpoints/{id}/secret", id).retrieve().body(JsonNode.class)
                .at("/secret").asString()).isEqualTo(secret);
    }

    @Test
    void rotationKeepsTheOldSecretFor24Hours() {
        RestClient c = client(newTenant().apiKey());
        JsonNode ep = create(c, Map.of("url", URL)).getBody();
        UUID id = UUID.fromString(ep.at("/id").asString());
        String oldSecret = ep.at("/secret").asString();

        String newSecret = c.post().uri("/v1/endpoints/{id}/rotate-secret", id).retrieve().body(JsonNode.class)
                .at("/secret").asString();

        assertThat(endpoints.activeSecrets(id, clock.instant())).containsExactly(newSecret, oldSecret);
        clock.advance(Duration.ofHours(25));
        assertThat(endpoints.activeSecrets(id, clock.instant())).containsExactly(newSecret);
    }

    @Test
    void internalAddressesAreRejected() {
        RestClient c = client(newTenant().apiKey());
        for (String url : List.of("http://169.254.169.254/latest/meta-data", "http://10.0.0.1/", "ftp://x.example/")) {
            ResponseEntity<JsonNode> res = create(c, Map.of("url", url));
            assertThat(res.getStatusCode().value()).as(url).isEqualTo(422);
            assertThat(res.getBody().at("/type").asString()).endsWith("/ssrf-blocked");
        }
    }

    @Test
    void unknownEventTypeIsRejected() {
        RestClient c = client(newTenant().apiKey());
        assertThat(create(c, Map.of("url", URL, "eventTypes", List.of("nope.nope"))).getStatusCode().value())
                .isEqualTo(422);
    }

    @Test
    void pauseAndResume() {
        RestClient c = client(newTenant().apiKey());
        String id = create(c, Map.of("url", URL)).getBody().at("/id").asString();
        assertThat(c.post().uri("/v1/endpoints/{id}/pause", id).retrieve().body(JsonNode.class)
                .at("/status").asString()).isEqualTo("PAUSED");
        assertThat(c.post().uri("/v1/endpoints/{id}/resume", id).retrieve().body(JsonNode.class)
                .at("/status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void patchUpdatesFieldsAndDeleteHidesTheEndpoint() {
        RestClient c = client(newTenant().apiKey());
        eventType(c, "order.created");
        String id = create(c, Map.of("url", URL)).getBody().at("/id").asString();
        JsonNode patched = c.patch().uri("/v1/endpoints/{id}", id)
                .body(Map.of("description", "new", "eventTypes", List.of("order.created"), "rateLimitPerSec", 5))
                .retrieve().body(JsonNode.class);
        assertThat(patched.at("/description").asString()).isEqualTo("new");
        assertThat(patched.at("/eventTypes/0").asString()).isEqualTo("order.created");
        assertThat(patched.at("/rateLimitPerSec").asInt()).isEqualTo(5);

        assertThat(c.delete().uri("/v1/endpoints/{id}", id).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(204);
        assertThat(c.get().uri("/v1/endpoints/{id}", id).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void otherTenantsEndpointsAre404() {
        RestClient a = client(newTenant().apiKey());
        RestClient b = client(newTenant().apiKey());
        String id = create(a, Map.of("url", URL)).getBody().at("/id").asString();
        assertThat(b.get().uri("/v1/endpoints/{id}", id).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void subscribersRespectSystemEventRules() {
        Tenant t = newTenant();
        RestClient c = client(t.apiKey());
        eventType(c, "order.created");
        String all = create(c, Map.of("url", URL)).getBody().at("/id").asString();
        String orders = create(c, Map.of("url", URL, "eventTypes", List.of("order.created"))).getBody()
                .at("/id").asString();
        String ops = create(c, Map.of("url", URL, "eventTypes", List.of("hookrelay.delivery.dead"))).getBody()
                .at("/id").asString();
        UUID app = UUID.fromString(t.appId());

        assertThat(endpoints.subscribers(app, "order.created")).extracting(e -> e.getId().toString())
                .containsExactlyInAnyOrder(all, orders);
        assertThat(endpoints.subscribers(app, "hookrelay.delivery.dead")).extracting(e -> e.getId().toString())
                .containsExactly(ops);
    }

    @Test
    void listPaginates() {
        RestClient c = client(newTenant().apiKey());
        for (int i = 0; i < 3; i++) {
            create(c, Map.of("url", URL));
        }
        JsonNode page = c.get().uri("/v1/endpoints?limit=2").retrieve().body(JsonNode.class);
        assertThat(page.at("/data").size()).isEqualTo(2);
        JsonNode next = c.get().uri("/v1/endpoints?limit=2&cursor={c}", page.at("/nextCursor").asString())
                .retrieve().body(JsonNode.class);
        assertThat(next.at("/data").size()).isEqualTo(1);
    }
}
