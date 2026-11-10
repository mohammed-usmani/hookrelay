package dev.hookrelay.server.delivery;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    long countByEventId(UUID eventId);

    @Query("""
            select d.id from Delivery d where d.endpointId = :endpointId
              and d.status in (dev.hookrelay.server.delivery.DeliveryStatus.PENDING,
                               dev.hookrelay.server.delivery.DeliveryStatus.FAILED_RETRYING)
            order by d.createdAt""")
    java.util.List<UUID> unfinishedIds(UUID endpointId);

    @Query("""
            select d.id from Delivery d where d.endpointId = :endpointId and d.parkedAt is not null
              and d.status in (dev.hookrelay.server.delivery.DeliveryStatus.PENDING,
                               dev.hookrelay.server.delivery.DeliveryStatus.FAILED_RETRYING)
            order by d.createdAt""")
    java.util.List<UUID> parkedIds(UUID endpointId);

    @Query("""
            select d.id from Delivery d where d.applicationId = :appId and d.endpointId = :endpointId
              and d.status = dev.hookrelay.server.delivery.DeliveryStatus.DEAD
              and d.createdAt >= :since and d.id > :after
            order by d.id""")
    java.util.List<UUID> deadSince(UUID appId, UUID endpointId, java.time.Instant since, UUID after,
                                   org.springframework.data.domain.Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findForUpdate(UUID id);
}
