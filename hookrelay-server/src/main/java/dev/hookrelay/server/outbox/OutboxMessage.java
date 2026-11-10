package dev.hookrelay.server.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A message waiting to be published to RabbitMQ. Written in the same transaction as the state
 * change that needs it, so a committed change can never lose its message.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    public enum Target { WORK, DEAD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID deliveryId;

    @Column(nullable = false)
    private UUID dispatchToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Target target;

    private String delayTier;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxMessage() { }

    public OutboxMessage(UUID deliveryId, UUID dispatchToken, Target target, String delayTier, Instant now) {
        this.deliveryId = deliveryId;
        this.dispatchToken = dispatchToken;
        this.target = target;
        this.delayTier = delayTier;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public UUID getDeliveryId() { return deliveryId; }
    public UUID getDispatchToken() { return dispatchToken; }
    public Target getTarget() { return target; }
    public String getDelayTier() { return delayTier; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
