package dev.hookrelay.server.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.hookrelay.server.support.DeliveryTestSupport;
import dev.hookrelay.server.support.Workers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ReplayIT extends DeliveryTestSupport {

    private List<LoggedRequest> hits(String path) {
        return receiver.findAll(postRequestedFor(urlEqualTo(path)));
    }

    private String deliveryId(String eventId) {
        return deliveryOf(eventId).get("id").toString();
    }

    private ResponseEntity<JsonNode> replay(String eventId) {
        return api.post().uri("/v1/deliveries/{id}/replay", deliveryId(eventId)).retrieve().toEntity(JsonNode.class);
    }

    private void forceDead(String eventId) {
        jdbc.update("update deliveries set status = 'DEAD', dead_reason = 'retries_exhausted' where event_id = ?",
                eventId);
    }

    @Test
    void deadDeliveryReplaysWithTheSameWebhookId() {
        receiver.stubFor(post("/r").willReturn(aResponse().withStatus(500)));
        endpoint(receiverUrl("/r"), List.of());
        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(60)).until(() -> "DEAD".equals(status(eventId)));
        int before = hits("/r").size();

        receiver.stubFor(post("/r").willReturn(aResponse().withStatus(200)));
        ResponseEntity<JsonNode> res = replay(eventId);

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(eventId)));
        assertThat(hits("/r")).hasSize(before + 1);
        assertThat(hits("/r").get(before).getHeader("webhook-id")).isEqualTo(eventId);
        List<Integer> attemptNos = jdbc.queryForList(
                "select attempt_no from attempts where delivery_id = ? order by attempt_no", Integer.class,
                deliveryId(eventId));
        assertThat(attemptNos).hasSize(before + 1).doesNotHaveDuplicates();
    }

    @Test
    void replayingAnUnfinishedDeliveryIsAConflict() {
        Workers.stop(listeners);
        endpoint(receiverUrl("/r"), List.of());
        String eventId = send("{}");

        ResponseEntity<JsonNode> res = replay(eventId);

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().at("/type").asString()).endsWith("/not-final");
    }

    @Test
    void replayingAPurgedEventIsGone() {
        Workers.stop(listeners);
        endpoint(receiverUrl("/r"), List.of());
        String eventId = send("{}");
        forceDead(eventId);
        jdbc.update("update events set payload_purged_at = now(6) where id = ?", eventId);

        ResponseEntity<JsonNode> res = replay(eventId);

        assertThat(res.getStatusCode().value()).isEqualTo(410);
        assertThat(res.getBody().at("/type").asString()).endsWith("/payload-purged");
    }

    @Test
    void bulkReplayResendsDeadDeliveriesAndSkipsPurgedOnes() {
        Workers.stop(listeners);
        receiver.stubFor(post("/bulk").willReturn(aResponse().withStatus(200)));
        String id = endpoint(receiverUrl("/bulk"), List.of()).at("/id").asString();
        String a = send("{\"n\":1}");
        String b = send("{\"n\":2}");
        String c = send("{\"n\":3}");
        List.of(a, b, c).forEach(this::forceDead);
        jdbc.update("update events set payload_purged_at = now(6) where id = ?", c);
        Workers.start(listeners);

        JsonNode res = api.post().uri("/v1/endpoints/{id}/replay", id)
                .body(Map.of("since", "2000-01-01T00:00:00Z")).retrieve().body(JsonNode.class);

        assertThat(res.at("/replayed").asInt()).isEqualTo(2);
        assertThat(res.at("/skippedPurged").asInt()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(a)) && "SUCCEEDED".equals(status(b)));
        assertThat(status(c)).isEqualTo("DEAD");
    }

    @Test
    void resumingAPausedEndpointDeliversWhatWasParked() {
        receiver.stubFor(post("/p").willReturn(aResponse().withStatus(200)));
        String id = endpoint(receiverUrl("/p"), List.of()).at("/id").asString();
        api.post().uri("/v1/endpoints/{id}/pause", id).retrieve().toBodilessEntity();
        String a = send("{\"n\":1}");
        String b = send("{\"n\":2}");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "select count(*) from deliveries where endpoint_id = ? and parked_at is not null", Integer.class, id) == 2);
        assertThat(hits("/p")).isEmpty();

        api.post().uri("/v1/endpoints/{id}/resume", id).retrieve().toBodilessEntity();

        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(a)) && "SUCCEEDED".equals(status(b)));
        assertThat(jdbc.queryForObject("select circuit_state from endpoints where id = ?", String.class, id))
                .isEqualTo("CLOSED");
    }

    @Test
    void deletingAnEndpointKillsADeliveryWaitingInTheDelayQueue() {
        receiver.stubFor(post("/gone").willReturn(aResponse().withStatus(500)));
        String id = endpoint(receiverUrl("/gone"), List.of()).at("/id").asString();
        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(30)).until(() -> attempts(eventId) == 1);

        assertThat(api.delete().uri("/v1/endpoints/{id}", id).retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(204);

        await().atMost(Duration.ofSeconds(10)).until(() -> "DEAD".equals(status(eventId)));
        assertThat(deliveryOf(eventId).get("dead_reason")).isEqualTo("endpoint_deleted");
        // the delayed message comes back after its TTL; the worker must drop it without sending
        await().pollDelay(Duration.ofSeconds(4)).until(() -> true);
        assertThat(hits("/gone")).hasSize(1);
        assertThat(attempts(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox where delivery_id = ? and published_at is null",
                Integer.class, deliveryId(eventId))).isZero();
    }
}
