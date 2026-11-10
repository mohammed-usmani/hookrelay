package dev.hookrelay.server.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One HTTP try of a delivery: the delivery log. */
@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID deliveryId;

    @Column(nullable = false)
    private UUID endpointId;

    @Column(nullable = false)
    private int attemptNo;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private long durationMs;

    private Integer responseStatus;

    @Column(columnDefinition = "text")
    private String responseSnippet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SendResult.Outcome outcome;

    private String error;

    protected Attempt() { }

    Attempt(UUID id, UUID deliveryId, UUID endpointId, int attemptNo, Instant startedAt, SendResult r) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.endpointId = endpointId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.durationMs = r.durationMs();
        this.responseStatus = r.status();
        this.responseSnippet = r.bodySnippet();
        this.outcome = r.outcome();
        this.error = truncate(r.error(), 500);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getDeliveryId() { return deliveryId; }
    public UUID getEndpointId() { return endpointId; }
    public int getAttemptNo() { return attemptNo; }
    public Instant getStartedAt() { return startedAt; }
    public long getDurationMs() { return durationMs; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseSnippet() { return responseSnippet; }
    public SendResult.Outcome getOutcome() { return outcome; }
    public String getError() { return error; }
}
