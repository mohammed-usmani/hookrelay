package dev.hookrelay.server.jobs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hookrelay.server.config.RabbitTopology;
import dev.hookrelay.server.support.DeliveryTestSupport;
import dev.hookrelay.server.support.Workers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class JobsIT extends DeliveryTestSupport {

    @Autowired RetentionJob retention;
    @Autowired ExpirySweeper expiry;
    @Autowired StuckDeliverySweeper stuck;
    @Autowired AmqpAdmin amqp;
    @Autowired MeterRegistry meters;

    /** Expire held locks. Rows must stay: ShedLock remembers which lock rows it already created. */
    @BeforeEach
    void releaseLocks() {
        jdbc.update("update shedlock set lock_until = '2000-01-01'");
    }

    private void awaitPublished(String eventId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                select count(*) from outbox o join deliveries d on d.id = o.delivery_id
                where d.event_id = ? and o.published_at is null""", Integer.class, eventId) == 0);
    }

    @Test
    void payloadsArePurgedAfter30Days() {
        Workers.stop(listeners);
        endpoint(receiverUrl("/j"), List.of());
        String eventId = send("{\"secret\":\"pii\"}");

        clock.advance(Duration.ofDays(29));
        retention.run();
        assertThat(jdbc.queryForObject("select payload from events where id = ?", String.class, eventId)).isNotNull();

        clock.advance(Duration.ofDays(2));
        releaseLocks();
        retention.run();

        assertThat(jdbc.queryForObject("select payload from events where id = ?", String.class, eventId)).isNull();
        JsonNode event = api.get().uri("/v1/events/{id}", eventId).retrieve().body(JsonNode.class);
        assertThat(event.at("/payloadPurged").asBoolean()).isTrue();
    }

    @Test
    void attemptsAreDeletedAfter90Days() {
        receiver.stubFor(post("/j").willReturn(aResponse().withStatus(200)));
        endpoint(receiverUrl("/j"), List.of());
        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(eventId)));

        clock.advance(Duration.ofDays(91));
        retention.run();

        assertThat(attempts(eventId)).isZero();
        assertThat(status(eventId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void parkedDeliveriesPastTheirDeadlineExpire() {
        String id = endpoint(receiverUrl("/j"), List.of()).at("/id").asString();
        api.post().uri("/v1/endpoints/{id}/pause", id).retrieve().toBodilessEntity();
        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(10)).until(() -> deliveryOf(eventId).get("parked_at") != null);

        clock.advance(Duration.ofDays(3).plusMinutes(1));
        expiry.run();

        assertThat(status(eventId)).isEqualTo("DEAD");
        assertThat(deliveryOf(eventId).get("dead_reason")).isEqualTo("expired");
    }

    @Test
    void aDeliveryWhoseMessageWasLostIsRecovered() {
        receiver.stubFor(post("/j").willReturn(aResponse().withStatus(200)));
        Workers.stop(listeners);
        endpoint(receiverUrl("/j"), List.of());
        String eventId = send("{}");
        awaitPublished(eventId);
        amqp.purgeQueue(RabbitTopology.WORK_QUEUE, false);
        double before = meters.counter("hookrelay.deliveries.recovered").count();

        clock.advance(Duration.ofMinutes(10));
        stuck.run();
        assertThat(meters.counter("hookrelay.deliveries.recovered").count()).isEqualTo(before);

        clock.advance(Duration.ofMinutes(6));
        releaseLocks();
        stuck.run();
        Workers.start(listeners);

        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(eventId)));
        assertThat(meters.counter("hookrelay.deliveries.recovered").count()).isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    void parkedDeliveriesOfAnActiveEndpointAreRequeued() {
        receiver.stubFor(post("/j").willReturn(aResponse().withStatus(200)));
        Workers.stop(listeners);
        endpoint(receiverUrl("/j"), List.of());
        String eventId = send("{}");
        awaitPublished(eventId);
        amqp.purgeQueue(RabbitTopology.WORK_QUEUE, false);
        // as if the process died between "resume" committing and its re-queue step
        jdbc.update("update deliveries set parked_at = now(6) where event_id = ?", eventId);

        stuck.run();
        Workers.start(listeners);

        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(eventId)));
    }

    @Test
    void onlyOneInstanceRunsAJobAtATime() throws Exception {
        double before = meters.counter("hookrelay.jobs.runs", "job", "retention").count();
        CyclicBarrier start = new CyclicBarrier(2);
        Runnable go = () -> {
            try {
                start.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            retention.run();
        };
        CompletableFuture.allOf(CompletableFuture.runAsync(go), CompletableFuture.runAsync(go)).get();

        assertThat(meters.counter("hookrelay.jobs.runs", "job", "retention").count()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from shedlock where name = 'retention'", Integer.class))
                .isEqualTo(1);
    }
}
