package dev.hookrelay.server.tenant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    @Query("""
            select a from Application a
            where :afterCreated is null or a.createdAt < :afterCreated
               or (a.createdAt = :afterCreated and a.id < :afterId)
            order by a.createdAt desc, a.id desc""")
    List<Application> page(Instant afterCreated, UUID afterId, Limit limit);
}
