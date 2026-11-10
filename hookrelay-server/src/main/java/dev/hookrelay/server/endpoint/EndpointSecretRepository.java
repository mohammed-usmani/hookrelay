package dev.hookrelay.server.endpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface EndpointSecretRepository extends JpaRepository<EndpointSecret, UUID> {

    @Query("""
            select s from EndpointSecret s
            where s.endpointId = :endpointId and (s.expiresAt is null or s.expiresAt > :now)
            order by s.createdAt desc""")
    List<EndpointSecret> active(UUID endpointId, Instant now);
}
