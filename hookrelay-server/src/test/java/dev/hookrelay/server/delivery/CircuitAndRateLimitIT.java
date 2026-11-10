package dev.hookrelay.server.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hookrelay.server.support.DeliveryTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CircuitAndRateLimitIT extends DeliveryTestSupport {

    private int hits(String path) {
        return receiver.findAll(postRequestedFor(urlEqualTo(path))).size();
    }

    private Map<String, Object> endpointRow(String id) {
        return jdbc.queryForMap("select * from endpoints where id = ?", id);
    }

    @Test
    void circuitOpensStopsTrafficAndRecoversAfterAProbe() {
        receiver.stubFor(post("/cb").willReturn(aResponse().withStatus(500)));
        String id = endpoint(receiverUrl("/cb"), List.of()).at("/id").asString();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(send("{\"n\":" + i + "}"));
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> "OPEN".equals(endpointRow(id).get("circuit_state")));
        Instant openUntil = ((Timestamp) endpointRow(id).get("circuit_open_until")).toInstant();
        int hitsWhenOpened = hits("/cb");
        // in-flight attempts may land just after opening; after that nothing until the cooldown ends
        await().pollDelay(Duration.ofMillis(300)).until(() -> true);
        int settled = hits("/cb");
        while (Instant.now().isBefore(openUntil.minusMillis(200))) {
            assertThat(hits("/cb")).isEqualTo(settled);
            await().pollDelay(Duration.ofMillis(100)).until(() -> true);
        }
        assertThat(settled - hitsWhenOpened).isLessThanOrEqualTo(8);

        receiver.stubFor(post("/cb").willReturn(aResponse().withStatus(200)));
        await().atMost(Duration.ofSeconds(60)).until(() -> events.stream().allMatch(e -> "SUCCEEDED".equals(status(e))));
        assertThat(endpointRow(id).get("circuit_state")).isEqualTo("CLOSED");
        int attemptRows = jdbc.queryForObject("select count(*) from attempts where endpoint_id = ?", Integer.class, id);
        int counted = jdbc.queryForObject("select sum(attempt_count) from deliveries where endpoint_id = ?",
                Integer.class, id);
        assertThat(counted).as("deferrals never count as attempts").isEqualTo(attemptRows);
    }

    @Test
    void perEndpointRateLimitHoldsAcrossConsumers() {
        receiver.stubFor(post("/rl").willReturn(aResponse().withStatus(200)));
        String id = endpoint(receiverUrl("/rl"), List.of()).at("/id").asString();
        api.patch().uri("/v1/endpoints/{id}", id).body(Map.of("rateLimitPerSec", 2)).retrieve().toBodilessEntity();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(send("{\"n\":" + i + "}"));
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> events.stream().allMatch(e -> "SUCCEEDED".equals(status(e))));

        List<Long> times = receiver.findAll(postRequestedFor(urlEqualTo("/rl"))).stream()
                .map(r -> r.getLoggedDate().getTime()).sorted().toList();
        assertThat(times).hasSize(10);
        for (int i = 0; i < times.size(); i++) {
            long start = times.get(i);
            long inWindow = times.stream().filter(t -> t >= start && t < start + 1000).count();
            assertThat(inWindow).as("requests in the second after #" + i).isLessThanOrEqualTo(3);
        }
    }
}
