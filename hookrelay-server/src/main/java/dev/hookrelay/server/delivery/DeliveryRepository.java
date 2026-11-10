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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findForUpdate(UUID id);
}
