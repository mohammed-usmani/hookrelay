package dev.hookrelay.server.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An application's credential. Only a SHA-256 of the secret part is stored. */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID applicationId;

    @Column(nullable = false, columnDefinition = "char(8)")
    private String prefix;

    @Column(nullable = false, columnDefinition = "binary(32)")
    private byte[] secretHash;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    private Instant revokedAt;

    protected ApiKey() { }

    ApiKey(UUID id, UUID applicationId, String prefix, byte[] secretHash, String name, Instant now) {
        this.id = id;
        this.applicationId = applicationId;
        this.prefix = prefix;
        this.secretHash = secretHash;
        this.name = name;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public String getPrefix() { return prefix; }
    public byte[] getSecretHash() { return secretHash; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
