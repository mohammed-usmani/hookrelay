package dev.hookrelay.server.endpoint;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** A receiver URL plus what it subscribes to and its delivery health (circuit breaker state). */
@Entity
@Table(name = "endpoints")
public class Endpoint {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String url;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EndpointStatus status;

    private Integer rateLimitPerSec;

    /** Empty means "all non-system event types". */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "endpoint_event_types", joinColumns = @JoinColumn(name = "endpoint_id"))
    @Column(name = "event_type_name")
    private Set<String> eventTypes = new TreeSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CircuitState circuitState = CircuitState.CLOSED;

    @Column(nullable = false)
    private int consecutiveFailures;

    private Instant circuitOpenUntil;

    @Column(nullable = false, columnDefinition = "tinyint")
    private int circuitLevel;

    private Instant lastSuccessAt;

    private Instant firstFailureAt;

    private Instant deletedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Endpoint() { }

    Endpoint(UUID id, UUID applicationId, String url, String description, Set<String> eventTypes,
             Integer rateLimitPerSec, Instant now) {
        this.id = id;
        this.applicationId = applicationId;
        this.url = url;
        this.description = description;
        this.eventTypes = new TreeSet<>(eventTypes);
        this.rateLimitPerSec = rateLimitPerSec;
        this.status = EndpointStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public EndpointStatus getStatus() { return status; }
    public Integer getRateLimitPerSec() { return rateLimitPerSec; }
    public Set<String> getEventTypes() { return eventTypes; }
    public CircuitState getCircuitState() { return circuitState; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public int getCircuitLevel() { return circuitLevel; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getFirstFailureAt() { return firstFailureAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void setUrl(String url) { this.url = url; }
    void setDescription(String description) { this.description = description; }
    void setEventTypes(Set<String> types) { this.eventTypes.clear(); this.eventTypes.addAll(types); }
    void setRateLimitPerSec(Integer rateLimitPerSec) { this.rateLimitPerSec = rateLimitPerSec; }
    void touch(Instant now) { this.updatedAt = now; }
    void markDeleted(Instant now) { this.deletedAt = now; this.updatedAt = now; }

    public void setStatus(EndpointStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    /** Circuit breaker bookkeeping; only {@code CircuitBreaker} should call this. */
    public void setCircuit(CircuitState state, int consecutiveFailures, Instant openUntil, int level,
                           Instant lastSuccessAt, Instant firstFailureAt) {
        this.circuitState = state;
        this.consecutiveFailures = consecutiveFailures;
        this.circuitOpenUntil = openUntil;
        this.circuitLevel = level;
        this.lastSuccessAt = lastSuccessAt;
        this.firstFailureAt = firstFailureAt;
    }
}
