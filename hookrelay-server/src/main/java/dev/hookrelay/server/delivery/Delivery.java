package dev.hookrelay.server.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One event going to one endpoint. {@code dispatchToken} changes every time a work message is
 * queued for it; a message whose token no longer matches is stale and gets dropped, which is
 * what makes duplicate RabbitMQ deliveries harmless.
 */
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    private String deadReason;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private Instant deadlineAt;

    @Column(nullable = false)
    private UUID dispatchToken;

    private Instant parkedAt;

    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Delivery() { }

    public Delivery(UUID id, UUID applicationId, UUID eventId, UUID endpointId, Instant now, Instant deadline) {
        this.id = id;
        this.applicationId = applicationId;
        this.eventId = eventId;
        this.endpointId = endpointId;
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = now;
        this.deadlineAt = deadline;
        this.dispatchToken = UUID.randomUUID();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public UUID getEventId() { return eventId; }
    public UUID getEndpointId() { return endpointId; }
    public DeliveryStatus getStatus() { return status; }
    public String getDeadReason() { return deadReason; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public UUID getDispatchToken() { return dispatchToken; }
    public Instant getParkedAt() { return parkedAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** A new token invalidates every message already queued for this delivery. */
    UUID rotateToken(Instant nextAttemptAt, Instant now) {
        this.dispatchToken = UUID.randomUUID();
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = now;
        return dispatchToken;
    }

    void recordAttempt(DeliveryStatus status, String lastError, Instant now) {
        this.attemptCount++;
        this.status = status;
        this.lastError = lastError;
        this.updatedAt = now;
    }

    void markDead(String reason, Instant now) {
        this.status = DeliveryStatus.DEAD;
        this.deadReason = reason;
        this.parkedAt = null;
        this.updatedAt = now;
    }

    void park(Instant now) {
        this.parkedAt = now;
        this.updatedAt = now;
    }

    void unpark(Instant now) {
        this.parkedAt = null;
        this.updatedAt = now;
    }

    void resetForReplay(Instant now, Instant deadline) {
        this.status = DeliveryStatus.PENDING;
        this.deadReason = null;
        this.attemptCount = 0;
        this.parkedAt = null;
        this.deadlineAt = deadline;
        this.updatedAt = now;
    }
}
