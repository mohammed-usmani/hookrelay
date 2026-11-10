package dev.hookrelay.server.endpoint;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

    @Query("select e from Endpoint e where e.id = :id and e.applicationId = :appId and e.deletedAt is null")
    Optional<Endpoint> findLive(UUID appId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Endpoint e where e.id = :id")
    Optional<Endpoint> findForUpdate(UUID id);

    @Query("""
            select e from Endpoint e
            where e.applicationId = :appId and e.deletedAt is null
              and (:afterCreated is null or e.createdAt < :afterCreated
                   or (e.createdAt = :afterCreated and e.id < :afterId))
            order by e.createdAt desc, e.id desc""")
    List<Endpoint> page(UUID appId, Instant afterCreated, UUID afterId, Limit limit);

    @Query("""
            select e from Endpoint e
            where e.applicationId = :appId and e.deletedAt is null
              and e.status in (dev.hookrelay.server.endpoint.EndpointStatus.ACTIVE,
                               dev.hookrelay.server.endpoint.EndpointStatus.PAUSED)
              and (:eventType member of e.eventTypes or (e.eventTypes is empty and :system = false))""")
    List<Endpoint> subscribers(UUID appId, String eventType, boolean system);
}
