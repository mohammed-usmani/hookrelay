package dev.hookrelay.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The relay holds its claim while it waits for broker confirms; that must not block new outbox rows. */
class OutboxLockingIT extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void aRelayWaitingOnTheBrokerDoesNotBlockNewOutboxRows() throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CompletableFuture<Void> relay = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            // the relay's claim query, then a slow broker
            jdbc.queryForList("""
                    SELECT id FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100
                    FOR UPDATE SKIP LOCKED""");
            claimed.countDown();
            sleep(3000);
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        // a token no delivery has, so the worker drops the resulting message
        jdbc.update("""
                insert into outbox (delivery_id, dispatch_token, target, created_at)
                values (?, ?, 'WORK', utc_timestamp(6))""", UUID.randomUUID().toString(), UUID.randomUUID().toString());
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        relay.get(10, TimeUnit.SECONDS);
        assertThat(tookMs).as("insert while the relay waits for confirms").isLessThan(1000);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
