package dev.hookrelay.server.notify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.hookrelay.server.support.DeliveryTestSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class NotificationsIT extends DeliveryTestSupport {

    @Autowired ObjectMapper json;

    private List<LoggedRequest> hits(String path) {
        return receiver.findAll(postRequestedFor(urlEqualTo(path)));
    }

    private int eventsOfType(String type) {
        return jdbc.queryForObject("select count(*) from events where application_id = ? and event_type = ?",
                Integer.class, tenant.appId(), type);
    }

    @Test
    void deadDeliveryNotifiesSubscribedEndpoint() {
        receiver.stubFor(post("/down").willReturn(aResponse().withStatus(500)));
        receiver.stubFor(post("/ops").willReturn(aResponse().withStatus(200)));
        String downId = endpoint(receiverUrl("/down"), List.of("order.created")).at("/id").asString();
        endpoint(receiverUrl("/ops"), List.of("hookrelay.delivery.dead"));

        String eventId = send("{}");

        await().atMost(Duration.ofSeconds(60)).until(() -> hits("/ops").size() == 1);
        JsonNode body = json.readTree(hits("/ops").get(0).getBodyAsString());
        assertThat(body.at("/eventId").asString()).isEqualTo(eventId);
        assertThat(body.at("/endpointId").asString()).isEqualTo(downId);
        assertThat(body.at("/reason").asString()).isEqualTo("retries_exhausted");
        assertThat(body.at("/eventType").asString()).isEqualTo("order.created");
        assertThat(body.at("/attempts").asInt()).isEqualTo(8);
        assertThat(body.at("/deliveryId").asString()).isEqualTo(deliveryOf(eventId).get("id").toString());
    }

    @Test
    void aDeadNotificationNeverTriggersAnotherNotification() {
        receiver.stubFor(post("/down").willReturn(aResponse().withStatus(500)));
        receiver.stubFor(post("/ops-down").willReturn(aResponse().withStatus(500)));
        endpoint(receiverUrl("/down"), List.of("order.created"));
        String opsId = endpoint(receiverUrl("/ops-down"), List.of("hookrelay.delivery.dead")).at("/id").asString();

        send("{}");

        await().atMost(Duration.ofSeconds(90)).until(() -> jdbc.queryForObject(
                "select count(*) from deliveries where endpoint_id = ? and status = 'DEAD'", Integer.class, opsId) == 1);
        await().pollDelay(Duration.ofSeconds(3)).until(() -> true);
        assertThat(eventsOfType("hookrelay.delivery.dead")).isEqualTo(1);
    }

    @Test
    void endpointFailingForThreeDaysIsDisabledAndReported() {
        receiver.stubFor(post("/dying").willReturn(aResponse().withStatus(500)));
        receiver.stubFor(post("/ops").willReturn(aResponse().withStatus(200)));
        String dyingId = endpoint(receiverUrl("/dying"), List.of("order.created")).at("/id").asString();
        endpoint(receiverUrl("/ops"), List.of("hookrelay.endpoint.disabled"));

        String first = send("{\"n\":1}");
        // 8 failures now, then 3 days later more: persistently failing, not a one-off
        await().atMost(Duration.ofSeconds(30)).until(() -> "DEAD".equals(status(first)));
        clock.advance(Duration.ofDays(3).plusMinutes(1));
        String second = send("{\"n\":2}");

        await().atMost(Duration.ofSeconds(30)).until(() -> "DISABLED".equals(jdbc.queryForObject(
                "select status from endpoints where id = ?", String.class, dyingId)));
        await().atMost(Duration.ofSeconds(30)).until(() -> "DEAD".equals(status(second)));
        assertThat(deliveryOf(second).get("dead_reason")).isEqualTo("endpoint_disabled");
        await().atMost(Duration.ofSeconds(30)).until(() -> hits("/ops").size() == 1);
        JsonNode body = json.readTree(hits("/ops").get(0).getBodyAsString());
        assertThat(body.at("/endpointId").asString()).isEqualTo(dyingId);
        assertThat(body.at("/reason").asString()).isEqualTo("no_success_3_days");
    }

    @Test
    void testSendGoesOnlyToThatEndpoint() {
        receiver.stubFor(post("/target").willReturn(aResponse().withStatus(200)));
        receiver.stubFor(post("/all").willReturn(aResponse().withStatus(200)));
        String id = endpoint(receiverUrl("/target"), List.of("order.created")).at("/id").asString();
        endpoint(receiverUrl("/all"), List.of());

        JsonNode res = api.post().uri("/v1/endpoints/{id}/test", id).retrieve().body(JsonNode.class);

        await().atMost(Duration.ofSeconds(30)).until(() -> hits("/target").size() == 1);
        assertThat(hits("/target").get(0).getHeader("webhook-id")).isEqualTo(res.at("/eventId").asString());
        JsonNode body = json.readTree(hits("/target").get(0).getBodyAsString());
        assertThat(body.at("/endpointId").asString()).isEqualTo(id);
        await().pollDelay(Duration.ofSeconds(2)).until(() -> true);
        assertThat(hits("/all")).isEmpty();
    }

    @Test
    void aSingleFailureAfterAQuietSpellDoesNotDisableTheEndpoint() {
        receiver.stubFor(post("/quiet").willReturn(aResponse().withStatus(500)));
        String id = endpoint(receiverUrl("/quiet"), List.of("order.created")).at("/id").asString();
        // last failure was 4 days ago, then nothing until now
        jdbc.update("update endpoints set first_failure_at = ?, consecutive_failures = 1 where id = ?",
                java.time.LocalDateTime.ofInstant(java.time.Instant.now(clock).minus(Duration.ofDays(4)),
                        java.time.ZoneOffset.UTC), id);

        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(30)).until(() -> attempts(eventId) >= 1);

        assertThat(jdbc.queryForObject("select status from endpoints where id = ?", String.class, id))
                .isEqualTo("ACTIVE");
    }
}
