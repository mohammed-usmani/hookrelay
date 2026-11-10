package dev.hookrelay.server.delivery;

import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.outbox.OutboxMessage;
import dev.hookrelay.server.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single way to get a delivery processed: gives it a fresh dispatch token and queues a
 * work message (via the outbox) in the caller's transaction.
 */
@Component
public class DeliveryDispatcher {

    private final OutboxRepository outbox;
    private final Clock clock;

    DeliveryDispatcher(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Queue now ({@code tier == null}) or after the tier's delay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Delivery d, DelayTier tier) {
        Instant now = Times.now(clock);
        Instant due = tier == null ? now : now.plus(tier.duration());
        UUID token = d.rotateToken(due, now);
        outbox.save(new OutboxMessage(d.getId(), token, OutboxMessage.Target.WORK,
                tier == null ? null : tier.routingKey(), now));
    }
}
