package dev.hookrelay.server.event;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Remembers an {@code Idempotency-Key} for 24 hours and the event it produced. */
@Entity
@Table(name = "idempotency_keys")
class IdempotencyRecord {

    @Embeddable
    record Key(UUID applicationId, @Column(name = "idem_key") String idemKey) implements Serializable { }

    @EmbeddedId
    private Key key;

    @Column(nullable = false, columnDefinition = "binary(32)")
    private byte[] bodySha256;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() { }

    IdempotencyRecord(Key key, byte[] bodySha256, UUID eventId, Instant now) {
        this.key = key;
        this.bodySha256 = bodySha256;
        this.eventId = eventId;
        this.createdAt = now;
    }

    byte[] getBodySha256() { return bodySha256; }
    UUID getEventId() { return eventId; }
    Instant getCreatedAt() { return createdAt; }
}
