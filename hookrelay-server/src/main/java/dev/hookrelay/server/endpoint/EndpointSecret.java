package dev.hookrelay.server.endpoint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A signing secret, AES-GCM encrypted. {@code expiresAt} is set when it is rotated out. */
@Entity
@Table(name = "endpoint_secrets")
class EndpointSecret {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID endpointId;

    @Column(nullable = false, columnDefinition = "varbinary(255)")
    private byte[] secretEnc;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant expiresAt;

    protected EndpointSecret() { }

    EndpointSecret(UUID id, UUID endpointId, byte[] secretEnc, Instant now) {
        this.id = id;
        this.endpointId = endpointId;
        this.secretEnc = secretEnc;
        this.createdAt = now;
    }

    byte[] getSecretEnc() { return secretEnc; }
    Instant getExpiresAt() { return expiresAt; }

    void expireAt(Instant at) {
        if (expiresAt == null || expiresAt.isAfter(at)) {
            expiresAt = at;
        }
    }
}
