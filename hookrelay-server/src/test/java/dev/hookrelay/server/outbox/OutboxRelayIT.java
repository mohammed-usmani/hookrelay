package dev.hookrelay.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hookrelay.server.config.RabbitTopology;
import dev.hookrelay.server.support.IntegrationTest;
import dev.hookrelay.server.support.Workers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxRelayIT extends IntegrationTest {

    @Autowired OutboxRelay relay;
    @Autowired OutboxRepository outbox;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void quiet() {
        Workers.stop(listeners);
        admin.purgeQueue(RabbitTopology.WORK_QUEUE, false);
    }

    @AfterEach
    void resume() {
        Workers.start(listeners);
    }

    private UUID insert(String tier) {
        UUID token = UUID.randomUUID();
        outbox.save(new OutboxMessage(UUID.randomUUID(), token, OutboxMessage.Target.WORK, tier, Instant.now()));
        return token;
    }

    private Set<String> drainTokens(int expected, Duration timeout) {
        return drainTokens(expected, timeout, false);
    }

    private Set<String> drainTokens(int expected, Duration timeout, boolean allowDuplicates) {
        Set<String> seen = new HashSet<>();
        List<String> all = new ArrayList<>();
        long end = System.nanoTime() + timeout.toNanos();
        while (all.size() < expected && System.nanoTime() < end) {
            Message m = rabbit.receive(RabbitTopology.WORK_QUEUE, 1000);
            if (m != null) {
                all.add(m.getMessageProperties().getMessageId());
                seen.add(m.getMessageProperties().getMessageId());
            }
        }
        // a short extra wait catches duplicates arriving late
        Message extra;
        while ((extra = rabbit.receive(RabbitTopology.WORK_QUEUE, 500)) != null) {
            all.add(extra.getMessageProperties().getMessageId());
        }
        if (!allowDuplicates) {
            assertThat(all).as("no duplicate messages").hasSameSizeAs(seen);
        }
        return seen;
    }

    private int unpublished(Set<UUID> tokens) {
        return (int) tokens.stream().filter(t -> jdbc.queryForObject(
                "select count(*) from outbox where dispatch_token = ? and published_at is null",
                Integer.class, t.toString()) > 0).count();
    }

    @Test
    void publishesAndMarksRows() {
        Set<UUID> tokens = Set.of(insert(null), insert(null), insert(null));
        relay.relayBatch();
        await().atMost(Duration.ofSeconds(10)).until(() -> unpublished(tokens) == 0);

        Set<String> received = drainTokens(3, Duration.ofSeconds(10));
        assertThat(received).containsAll(tokens.stream().map(UUID::toString).toList());
    }

    @Test
    void messageBodyNamesTheDeliveryAndToken() {
        UUID token = insert(null);
        relay.relayBatch();
        Message m = rabbit.receive(RabbitTopology.WORK_QUEUE, 10_000);
        assertThat(m).isNotNull();
        String body = new String(m.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"dispatchToken\":\"" + token + "\"").contains("\"deliveryId\":\"");
    }

    @Test
    void rowsWaitWhileTheBrokerIsDownAndGoOutAfter() {
        var docker = RABBIT.getDockerClient();
        docker.pauseContainerCmd(RABBIT.getContainerId()).exec();
        UUID token;
        try {
            token = insert(null);
            relay.relayBatch();
            assertThat(unpublished(Set.of(token))).isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(RABBIT.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> unpublished(Set.of(token)) == 0);
        // A send that was buffered while the broker was frozen may arrive as well as the re-send:
        // at-least-once. Both copies carry the same dispatch token, so the worker acts once.
        assertThat(drainTokens(1, Duration.ofSeconds(10), true)).contains(token.toString());
    }

    @Test
    void concurrentRelaysNeverPublishARowTwice() throws Exception {
        Set<UUID> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(insert(null));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(pool.submit(() -> {
                while (relay.relayBatch() > 0) { /* keep draining */ }
            }));
        }
        for (Future<?> f : runs) {
            f.get();
        }
        pool.shutdown();
        await().atMost(Duration.ofSeconds(30)).until(() -> unpublished(tokens) == 0);
        Set<String> received = drainTokens(500, Duration.ofSeconds(30));
        assertThat(received).containsAll(tokens.stream().map(UUID::toString).toList());
    }

    @Test
    void delayedMessagesArriveAfterTheirTier() {
        long start = System.nanoTime();
        UUID token = insert("1s");
        relay.relayBatch();
        Message m = rabbit.receive(RabbitTopology.WORK_QUEUE, 15_000);
        assertThat(m).isNotNull();
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(token.toString());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }
}
