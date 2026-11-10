package dev.hookrelay.server.eventtype;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A named kind of event an application sends, e.g. {@code order.created}. */
@Entity
@Table(name = "event_types")
public class EventType {

    public static final String SYSTEM_PREFIX = "hookrelay.";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Stored verbatim as text; Hibernate's JSON type would re-encode it as a JSON string. */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String jsonSchema;

    @Column(name = "`system`", nullable = false)
    private boolean system;

    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected EventType() { }

    EventType(UUID id, UUID applicationId, String name, String description, String jsonSchema,
              boolean system, Instant now) {
        this.id = id;
        this.applicationId = applicationId;
        this.name = name;
        this.description = description;
        this.jsonSchema = jsonSchema;
        this.system = system;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getJsonSchema() { return jsonSchema; }
    public boolean isSystem() { return system; }
    public Instant getArchivedAt() { return archivedAt; }
    public boolean isArchived() { return archivedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void update(String description, String jsonSchema, Instant now) {
        if (description != null) {
            this.description = description;
        }
        if (jsonSchema != null) {
            this.jsonSchema = jsonSchema;
        }
        this.updatedAt = now;
    }

    void archive(Instant now) {
        this.archivedAt = archivedAt == null ? now : archivedAt;
        this.updatedAt = now;
    }

    void unarchive(Instant now) {
        this.archivedAt = null;
        this.updatedAt = now;
    }
}
