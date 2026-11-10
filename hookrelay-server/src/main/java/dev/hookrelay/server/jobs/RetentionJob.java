package dev.hookrelay.server.jobs;

import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.config.HookRelayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Nightly: drops data past its retention period, 1,000 rows per transaction. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final HookRelayProperties.Retention retention;
    private final MeterRegistry meters;
    private final Clock clock;

    RetentionJob(EntityManager em, TransactionTemplate tx, HookRelayProperties props, MeterRegistry meters,
                 Clock clock) {
        this.em = em;
        this.tx = tx;
        this.retention = props.retention();
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(cron = "${hookrelay.jobs.retention-cron:0 30 2 * * *}")
    @SchedulerLock(name = "retention", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void run() {
        meters.counter("hookrelay.jobs.runs", "job", "retention").increment();
        Instant now = Times.now(clock);
        long payloads = batch("payloads", """
                update events set payload = null, payload_purged_at = :now
                where created_at < :cutoff and payload_purged_at is null limit 1000""",
                now.minus(retention.payloads()), now);
        long attempts = batch("attempts", "delete from attempts where started_at < :cutoff limit 1000",
                now.minus(retention.attempts()), null);
        long keys = batch("idempotency_keys", "delete from idempotency_keys where created_at < :cutoff limit 1000",
                now.minus(retention.idempotencyKeys()), null);
        long outbox = batch("outbox", "delete from outbox where published_at < :cutoff limit 1000",
                now.minus(retention.publishedOutbox()), null);
        log.info("Retention: purged {} payloads, deleted {} attempts, {} idempotency keys, {} outbox rows",
                payloads, attempts, keys, outbox);
    }

    private long batch(String what, String sql, Instant cutoff, Instant now) {
        long n = Batches.untilZero(() -> tx.execute(s -> {
            var q = em.createNativeQuery(sql).setParameter("cutoff", cutoff);
            if (now != null) {
                q.setParameter("now", now);
            }
            return q.executeUpdate();
        }));
        meters.counter("hookrelay.retention.rows", "table", what).increment(n);
        return n;
    }
}
