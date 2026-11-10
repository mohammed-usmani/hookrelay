package dev.hookrelay.server.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant: one sending system (e.g. "Acme Shop") with its own keys, event types and endpoints. */
@Entity
@Table(name = "applications")
public class Application {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Application() { }

    public Application(UUID id, String name, Instant now) {
        this.id = id;
        this.name = name;
        this.status = ApplicationStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public ApplicationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    void setStatus(ApplicationStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }
}
