package dev.hookrelay.server.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hookrelay.server.support.DeliveryTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Every app-scoped resource route, called by another tenant with valid IDs, must look absent. */
class TenantIsolationIT extends DeliveryTestSupport {

    record Route(HttpMethod method, String uri, Object body) { }

    @Test
    void anotherTenantGets404OnEveryResourceRoute() {
        receiver.stubFor(post("/iso").willReturn(aResponse().withStatus(200)));
        String ep = endpoint(receiverUrl("/iso"), List.of()).at("/id").asString();
        String ev = send("{}");
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(ev)));
        String dl = deliveryOf(ev).get("id").toString();

        List<Route> routes = List.of(
                new Route(HttpMethod.GET, "/v1/events/" + ev, null),
                new Route(HttpMethod.GET, "/v1/events/" + ev + "/deliveries", null),
                new Route(HttpMethod.GET, "/v1/deliveries/" + dl, null),
                new Route(HttpMethod.GET, "/v1/deliveries/" + dl + "/attempts", null),
                new Route(HttpMethod.POST, "/v1/deliveries/" + dl + "/replay", null),
                new Route(HttpMethod.GET, "/v1/endpoints/" + ep, null),
                new Route(HttpMethod.PATCH, "/v1/endpoints/" + ep, Map.of("description", "x")),
                new Route(HttpMethod.POST, "/v1/endpoints/" + ep + "/pause", null),
                new Route(HttpMethod.POST, "/v1/endpoints/" + ep + "/resume", null),
                new Route(HttpMethod.POST, "/v1/endpoints/" + ep + "/rotate-secret", null),
                new Route(HttpMethod.GET, "/v1/endpoints/" + ep + "/secret", null),
                new Route(HttpMethod.POST, "/v1/endpoints/" + ep + "/test", null),
                new Route(HttpMethod.GET, "/v1/endpoints/" + ep + "/stats", null),
                new Route(HttpMethod.POST, "/v1/endpoints/" + ep + "/replay", Map.of("since", "2000-01-01T00:00:00Z")),
                new Route(HttpMethod.DELETE, "/v1/endpoints/" + ep, null),
                new Route(HttpMethod.GET, "/v1/event-types/order.created", null),
                new Route(HttpMethod.PATCH, "/v1/event-types/order.created", Map.of("description", "x")),
                new Route(HttpMethod.POST, "/v1/event-types/order.created/archive", null));

        RestClient other = client(newTenant().apiKey());
        List<String> leaks = new ArrayList<>();
        for (Route r : routes) {
            RestClient.RequestBodySpec req = other.method(r.method()).uri(r.uri());
            if (r.body() != null) {
                req.body(r.body());
            }
            int code = req.retrieve().toBodilessEntity().getStatusCode().value();
            if (code != 404) {
                leaks.add(r.method() + " " + r.uri() + " → " + code);
            }
        }
        assertThat(leaks).as("routes that did not hide another tenant's resource").isEmpty();

        // and nothing changed for the owner
        JsonNode mine = api.get().uri("/v1/endpoints/{id}", ep).retrieve().body(JsonNode.class);
        assertThat(mine.at("/status").asString()).isEqualTo("ACTIVE");
        assertThat(mine.at("/description").isNull() || mine.at("/description").asString().isEmpty()).isTrue();
    }

    @Test
    void listsNeverShowAnotherTenantsRows() {
        receiver.stubFor(post("/iso").willReturn(aResponse().withStatus(200)));
        String ep = endpoint(receiverUrl("/iso"), List.of()).at("/id").asString();
        send("{}");
        RestClient other = client(newTenant().apiKey());

        for (String uri : List.of("/v1/events", "/v1/deliveries", "/v1/endpoints",
                "/v1/deliveries?endpointId=" + ep)) {
            JsonNode page = other.get().uri(uri).retrieve().body(JsonNode.class);
            assertThat(page.at("/data").size()).as(uri).isZero();
        }
    }
}
