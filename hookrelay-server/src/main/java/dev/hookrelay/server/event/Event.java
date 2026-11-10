package dev.hookrelay.server.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Something that happened in an application. Never edited; only the payload is removed by
 * the retention job. The id doubles as the {@code webhook-id} receivers dedupe on.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String eventType;

    /** Exactly the bytes the sender posted (UTF-8), so signatures cover what they sent. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String payload;

    private Instant payloadPurgedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected Event() { }

    public Event(UUID id, UUID applicationId, String eventType, String payload, Instant now) {
        this.id = id;
        this.applicationId = applicationId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getPayloadPurgedAt() { return payloadPurgedAt; }
    public boolean isPayloadPurged() { return payloadPurgedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
}
