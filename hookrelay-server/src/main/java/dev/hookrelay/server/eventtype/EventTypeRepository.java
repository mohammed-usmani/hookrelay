package dev.hookrelay.server.eventtype;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventTypeRepository extends JpaRepository<EventType, UUID> {

    Optional<EventType> findByApplicationIdAndName(UUID applicationId, String name);

    boolean existsByApplicationIdAndName(UUID applicationId, String name);

    long countByApplicationIdAndNameIn(UUID applicationId, Collection<String> names);

    @Query("""
            select t from EventType t
            where t.applicationId = :appId
              and (:afterCreated is null or t.createdAt < :afterCreated
                   or (t.createdAt = :afterCreated and t.id < :afterId))
            order by t.createdAt desc, t.id desc""")
    List<EventType> page(UUID appId, Instant afterCreated, UUID afterId, Limit limit);
}
