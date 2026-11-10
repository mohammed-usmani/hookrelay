package dev.hookrelay.server.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByIdAndApplicationId(UUID id, UUID applicationId);

    @org.springframework.data.jpa.repository.Query("""
            select e from Event e
            where e.applicationId = :appId
              and (:eventType is null or e.eventType = :eventType)
              and (:from is null or e.createdAt >= :from)
              and (:to is null or e.createdAt < :to)
              and (:afterCreated is null or e.createdAt < :afterCreated
                   or (e.createdAt = :afterCreated and e.id < :afterId))
            order by e.createdAt desc, e.id desc""")
    java.util.List<Event> page(java.util.UUID appId, String eventType, java.time.Instant from, java.time.Instant to,
                               java.time.Instant afterCreated, java.util.UUID afterId,
                               org.springframework.data.domain.Limit limit);
}
