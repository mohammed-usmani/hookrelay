package dev.hookrelay.server.outbox;

import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.config.RabbitTopology;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed outbox rows to RabbitMQ. Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so any number of instances can run this at once without
 * publishing a row twice. A row is marked published only after the broker confirms it; rows
 * the broker didn't confirm stay for the next round.
 */
@Component
public class OutboxRelay {

    static final int BATCH = 100;
    private static final long CONFIRM_TIMEOUT_MS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String deliveryId, String token, String target, String delayTier) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final Clock clock;

    OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, RabbitTemplate rabbit, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rabbit = rabbit;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hookrelay.outbox.poll-interval:200ms}")
    void poll() {
        try {
            while (relayBatch() == BATCH) {
                // a full batch means more may be waiting
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay round failed: {}", e.toString());
        }
    }

    /** Publishes up to one batch; returns how many rows were confirmed and marked. */
    public int relayBatch() {
        Integer confirmed = tx.execute(s -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, delivery_id, dispatch_token, target, delay_tier FROM outbox
                    WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5)),
                    BATCH);
            if (rows.isEmpty()) {
                return 0;
            }
            List<Long> ok = publish(rows);
            if (!ok.isEmpty()) {
                Timestamp now = Timestamp.from(Times.now(clock));
                jdbc.batchUpdate("UPDATE outbox SET published_at = ? WHERE id = ?",
                        ok.stream().map(id -> new Object[] {now, id}).toList());
            }
            return ok.size();
        });
        return confirmed == null ? 0 : confirmed;
    }

    private List<Long> publish(List<Row> rows) {
        List<CorrelationData> pending = new ArrayList<>();
        try {
            for (Row r : rows) {
                CorrelationData cd = new CorrelationData(Long.toString(r.id()));
                rabbit.send(exchange(r), routingKey(r), message(r), cd);
                pending.add(cd);
            }
        } catch (RuntimeException e) {
            log.warn("Publishing to RabbitMQ failed, will retry: {}", e.toString());
        }
        List<Long> ok = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONFIRM_TIMEOUT_MS);
        for (CorrelationData cd : pending) {
            try {
                long left = Math.max(1, deadline - System.nanoTime());
                CorrelationData.Confirm c = cd.getFuture().get(left, TimeUnit.NANOSECONDS);
                if (c.ack() && cd.getReturned() == null) {
                    ok.add(Long.parseLong(cd.getId()));
                }
            } catch (TimeoutException e) {
                break; // broker is not answering; the rest stays for the next round
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
        return ok;
    }

    private static String exchange(Row r) {
        if ("DEAD".equals(r.target())) {
            return RabbitTopology.DEAD_EXCHANGE;
        }
        return r.delayTier() == null ? RabbitTopology.WORK_EXCHANGE : RabbitTopology.DELAY_EXCHANGE;
    }

    private static String routingKey(Row r) {
        if ("DEAD".equals(r.target())) {
            return RabbitTopology.DEAD_KEY;
        }
        return r.delayTier() == null ? RabbitTopology.WORK_KEY : r.delayTier();
    }

    private static Message message(Row r) {
        String body = "{\"deliveryId\":\"" + r.deliveryId() + "\",\"dispatchToken\":\"" + r.token() + "\"}";
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(r.token())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
