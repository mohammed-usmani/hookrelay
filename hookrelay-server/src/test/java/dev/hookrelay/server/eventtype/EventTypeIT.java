package dev.hookrelay.server.eventtype;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class EventTypeIT extends IntegrationTest {

    private ResponseEntity<JsonNode> create(RestClient c, Map<String, Object> body) {
        return c.post().uri("/v1/event-types").body(body).retrieve().toEntity(JsonNode.class);
    }

    @Test
    void createGetAndList() {
        RestClient c = client(newTenant().apiKey());
        ResponseEntity<JsonNode> created = create(c, Map.of("name", "order.created", "description", "New order"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        JsonNode got = c.get().uri("/v1/event-types/order.created").retrieve().body(JsonNode.class);
        assertThat(got.at("/description").asString()).isEqualTo("New order");
        assertThat(got.at("/system").asBoolean()).isFalse();

        JsonNode list = c.get().uri("/v1/event-types").retrieve().body(JsonNode.class);
        List<String> names = StreamSupport.stream(list.at("/data").spliterator(), false)
                .map(n -> n.at("/name").asString()).toList();
        assertThat(names).contains("order.created");
    }

    @Test
    void duplicateNameIsConflict() {
        RestClient c = client(newTenant().apiKey());
        create(c, Map.of("name", "order.created"));
        assertThat(create(c, Map.of("name", "order.created")).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void badNameIsValidationError() {
        RestClient c = client(newTenant().apiKey());
        assertThat(create(c, Map.of("name", "Order Created")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void newApplicationHasTheSystemTypes() {
        RestClient c = client(newTenant().apiKey());
        JsonNode list = c.get().uri("/v1/event-types?limit=200").retrieve().body(JsonNode.class);
        List<String> system = StreamSupport.stream(list.at("/data").spliterator(), false)
                .filter(n -> n.at("/system").asBoolean()).map(n -> n.at("/name").asString()).toList();
        assertThat(system).containsExactlyInAnyOrder(
                "hookrelay.endpoint.disabled", "hookrelay.delivery.dead", "hookrelay.test");
    }

    @Test
    void reservedPrefixCannotBeCreated() {
        RestClient c = client(newTenant().apiKey());
        assertThat(create(c, Map.of("name", "hookrelay.foo")).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void systemTypesCannotBeArchivedOrEdited() {
        RestClient c = client(newTenant().apiKey());
        assertThat(c.post().uri("/v1/event-types/hookrelay.test/archive").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(409);
        assertThat(c.patch().uri("/v1/event-types/hookrelay.test").body(Map.of("description", "x"))
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void archiveAndUnarchive() {
        RestClient c = client(newTenant().apiKey());
        create(c, Map.of("name", "order.created"));
        c.post().uri("/v1/event-types/order.created/archive").retrieve().toBodilessEntity();
        assertThat(c.get().uri("/v1/event-types/order.created").retrieve().body(JsonNode.class)
                .at("/archived").asBoolean()).isTrue();
        c.post().uri("/v1/event-types/order.created/unarchive").retrieve().toBodilessEntity();
        assertThat(c.get().uri("/v1/event-types/order.created").retrieve().body(JsonNode.class)
                .at("/archived").asBoolean()).isFalse();
    }

    @Test
    void invalidSchemaIsBadRequestAndValidSchemaIsStored() {
        RestClient c = client(newTenant().apiKey());
        assertThat(create(c, Map.of("name", "a.b", "schema", Map.of("type", 5))).getStatusCode().value())
                .isEqualTo(400);
        create(c, Map.of("name", "a.c", "schema", Map.of("type", "object")));
        assertThat(c.get().uri("/v1/event-types/a.c").retrieve().body(JsonNode.class)
                .at("/schema/type").asString()).isEqualTo("object");
    }

    @Test
    void otherTenantsTypesAreInvisible() {
        RestClient a = client(newTenant().apiKey());
        RestClient b = client(newTenant().apiKey());
        create(a, Map.of("name", "order.created"));
        assertThat(b.get().uri("/v1/event-types/order.created").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(404);
    }
}
