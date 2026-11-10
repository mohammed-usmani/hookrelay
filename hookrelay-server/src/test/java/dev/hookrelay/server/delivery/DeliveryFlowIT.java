package dev.hookrelay.server.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.hookrelay.receiver.WebhookVerifier;
import dev.hookrelay.server.config.RabbitTopology;
import dev.hookrelay.server.support.DeliveryTestSupport;
import dev.hookrelay.server.support.Workers;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class DeliveryFlowIT extends DeliveryTestSupport {

    @Autowired RabbitTemplate rabbit;

    private static final Duration WAIT = Duration.ofSeconds(30);

    private List<LoggedRequest> hits(String path) {
        return receiver.findAll(postRequestedFor(urlEqualTo(path)));
    }

    private static Map<String, String> headers(LoggedRequest r) {
        return Map.of("webhook-id", r.getHeader("webhook-id"),
                "webhook-timestamp", r.getHeader("webhook-timestamp"),
                "webhook-signature", r.getHeader("webhook-signature"));
    }

    @Test
    void happyPathDeliversOnceWithAValidSignature() {
        receiver.stubFor(post("/ok").willReturn(aResponse().withStatus(200)));
        String secret = endpoint(receiverUrl("/ok"), List.of()).at("/secret").asString();

        String eventId = send("{\"id\":42}");

        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(status(eventId)));
        assertThat(attempts(eventId)).isEqualTo(1);
        List<LoggedRequest> got = hits("/ok");
        assertThat(got).hasSize(1);
        assertThat(got.get(0).getHeader("webhook-id")).isEqualTo(eventId);
        assertThat(got.get(0).getHeader("hookrelay-event-type")).isEqualTo("order.created");
        new WebhookVerifier(secret, Clock.systemUTC()).verify(headers(got.get(0)), got.get(0).getBody());
    }

    @Test
    void failsThenSucceeds() {
        receiver.stubFor(post("/flaky").inScenario("f").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("2"));
        receiver.stubFor(post("/flaky").inScenario("f").whenScenarioStateIs("2")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("3"));
        receiver.stubFor(post("/flaky").inScenario("f").whenScenarioStateIs("3")
                .willReturn(aResponse().withStatus(200)));
        endpoint(receiverUrl("/flaky"), List.of());

        String eventId = send("{}");

        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(status(eventId)));
        assertThat(attempts(eventId)).isEqualTo(3);
        List<LoggedRequest> got = hits("/flaky");
        Duration gap = Duration.between(got.get(1).getLoggedDate().toInstant(), got.get(2).getLoggedDate().toInstant());
        assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    @Test
    void exhaustsRetriesThenGoesDead() {
        receiver.stubFor(post("/down").willReturn(aResponse().withStatus(500)));
        endpoint(receiverUrl("/down"), List.of());

        String eventId = send("{}");

        await().atMost(Duration.ofSeconds(60)).until(() -> "DEAD".equals(status(eventId)));
        assertThat(deliveryOf(eventId).get("dead_reason")).isEqualTo("retries_exhausted");
        assertThat(attempts(eventId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox o join deliveries d on d.id = o.delivery_id
                where d.event_id = ? and o.target = 'DEAD'""", Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void aDuplicatedMessageSendsOnlyOnce() {
        receiver.stubFor(post("/dup").willReturn(aResponse().withStatus(200)));
        endpoint(receiverUrl("/dup"), List.of());
        Workers.stop(listeners);
        String eventId = send("{}");
        await().atMost(WAIT).until(() -> jdbc.queryForObject("""
                select count(*) from outbox o join deliveries d on d.id = o.delivery_id
                where d.event_id = ? and o.published_at is not null""", Integer.class, eventId) == 1);
        Map<String, Object> d = deliveryOf(eventId);
        String body = "{\"deliveryId\":\"" + d.get("id") + "\",\"dispatchToken\":\"" + d.get("dispatch_token") + "\"}";
        rabbit.send(RabbitTopology.WORK_EXCHANGE, RabbitTopology.WORK_KEY,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).build());

        Workers.start(listeners);

        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(status(eventId)));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> hits("/dup").size() == 1);
    }

    @Test
    void pausedEndpointParksDeliveries() {
        receiver.stubFor(post("/paused").willReturn(aResponse().withStatus(200)));
        String id = endpoint(receiverUrl("/paused"), List.of()).at("/id").asString();
        api.post().uri("/v1/endpoints/{id}/pause", id).retrieve().toBodilessEntity();

        String eventId = send("{}");

        await().atMost(WAIT).until(() -> deliveryOf(eventId).get("parked_at") != null);
        assertThat(hits("/paused")).isEmpty();
        assertThat(status(eventId)).isEqualTo("PENDING");
    }

    @Test
    void pastTheDeadlineADeliveryExpires() {
        receiver.stubFor(post("/late").willReturn(aResponse().withStatus(200)));
        endpoint(receiverUrl("/late"), List.of());
        Workers.stop(listeners);
        String eventId = send("{}");
        clock.advance(Duration.ofDays(3).plusSeconds(1));

        Workers.start(listeners);

        await().atMost(WAIT).until(() -> "DEAD".equals(status(eventId)));
        assertThat(deliveryOf(eventId).get("dead_reason")).isEqualTo("expired");
        assertThat(hits("/late")).isEmpty();
    }

    @Test
    void receiverGetsTheExactBytesAndTheSignatureVerifies() {
        receiver.stubFor(post("/exact").willReturn(aResponse().withStatus(200)));
        String secret = endpoint(receiverUrl("/exact"), List.of()).at("/secret").asString();
        String payload = "{\"z\":1,  \"a\":\"héllo ☃\" ,\"n\":1.50}";

        String eventId = send(payload);

        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(status(eventId)));
        receiver.verify(postRequestedFor(urlEqualTo("/exact"))
                .withRequestBody(binaryEqualTo(payload.getBytes(StandardCharsets.UTF_8))));
        LoggedRequest r = hits("/exact").get(0);
        new WebhookVerifier(secret, Clock.systemUTC()).verify(headers(r), r.getBody());
    }

    @Test
    void dnsRebindingToAnInternalAddressIsBlockedAtSendTime() {
        dns.map("rebind.test", "127.0.0.1");
        endpoint("http://rebind.test:" + receiver.port() + "/rebind", List.of());
        dns.map("rebind.test", "127.0.0.2");

        String eventId = send("{}");

        await().atMost(WAIT).until(() -> attempts(eventId) >= 1);
        String outcome = jdbc.queryForObject("""
                select a.outcome from attempts a join deliveries d on d.id = a.delivery_id
                where d.event_id = ? order by a.attempt_no limit 1""", String.class, eventId);
        assertThat(outcome).isEqualTo("SSRF_BLOCKED");
        assertThat(hits("/rebind")).isEmpty();
    }

    @Test
    void attemptsRecordStatusAndDuration() {
        receiver.stubFor(post("/rec").willReturn(aResponse().withStatus(201).withBody("thanks")));
        endpoint(receiverUrl("/rec"), List.of());
        String eventId = send("{}");
        await().atMost(WAIT).until(() -> "SUCCEEDED".equals(status(eventId)));
        Map<String, Object> a = jdbc.queryForMap("""
                select a.* from attempts a join deliveries d on d.id = a.delivery_id where d.event_id = ?""", eventId);
        assertThat(a.get("response_status")).isEqualTo(201);
        assertThat(a.get("response_snippet")).isEqualTo("thanks");
        assertThat(a.get("outcome")).isEqualTo("SUCCESS");
        assertThat(((Number) a.get("duration_ms")).longValue()).isNotNegative();
        assertThat(a.get("started_at")).isInstanceOf(Timestamp.class);
    }
}
