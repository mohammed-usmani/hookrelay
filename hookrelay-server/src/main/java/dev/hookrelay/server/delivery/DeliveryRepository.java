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

    @Query("""
            select d from Delivery d
            where d.applicationId = :appId
              and (:status is null or d.status = :status)
              and (:endpointId is null or d.endpointId = :endpointId)
              and (:eventId is null or d.eventId = :eventId)
              and (:from is null or d.createdAt >= :from)
              and (:to is null or d.createdAt < :to)
              and (:afterCreated is null or d.createdAt < :afterCreated
                   or (d.createdAt = :afterCreated and d.id < :afterId))
            order by d.createdAt desc, d.id desc""")
    java.util.List<Delivery> page(UUID appId, DeliveryStatus status, UUID endpointId, UUID eventId,
                                  java.time.Instant from, java.time.Instant to,
                                  java.time.Instant afterCreated, UUID afterId,
                                  org.springframework.data.domain.Limit limit);

    Optional<Delivery> findByIdAndApplicationId(UUID id, UUID applicationId);

    java.util.List<Delivery> findByEventIdAndApplicationIdOrderByCreatedAt(UUID eventId, UUID applicationId);

    @Query("""
            select d.status, count(d) from Delivery d
            where d.endpointId = :endpointId and d.createdAt >= :since group by d.status""")
    java.util.List<Object[]> countByStatusSince(UUID endpointId, java.time.Instant since);

    /** Unfinished, not parked, overdue, and nothing waiting in the outbox: its message was lost. */
    @Query("""
            select d.id from Delivery d
            where d.status in (dev.hookrelay.server.delivery.DeliveryStatus.PENDING,
                               dev.hookrelay.server.delivery.DeliveryStatus.FAILED_RETRYING)
              and d.parkedAt is null and d.nextAttemptAt < :staleBefore
              and not exists (select 1 from OutboxMessage o where o.deliveryId = d.id and o.publishedAt is null)
            order by d.nextAttemptAt""")
    java.util.List<UUID> stuckIds(java.time.Instant staleBefore, org.springframework.data.domain.Limit limit);

    @Query("""
            select d.id from Delivery d, dev.hookrelay.server.endpoint.Endpoint e
            where e.id = d.endpointId and d.parkedAt is not null
              and d.status in (dev.hookrelay.server.delivery.DeliveryStatus.PENDING,
                               dev.hookrelay.server.delivery.DeliveryStatus.FAILED_RETRYING)
              and e.status = dev.hookrelay.server.endpoint.EndpointStatus.ACTIVE and e.deletedAt is null""")
    java.util.List<UUID> parkedOnActiveIds(org.springframework.data.domain.Limit limit);

    @Query("""
            select d.id from Delivery d
            where d.parkedAt is not null and d.deadlineAt < :now
              and d.status in (dev.hookrelay.server.delivery.DeliveryStatus.PENDING,
                               dev.hookrelay.server.delivery.DeliveryStatus.FAILED_RETRYING)""")
    java.util.List<UUID> expiredParkedIds(java.time.Instant now, org.springframework.data.domain.Limit limit);
}
