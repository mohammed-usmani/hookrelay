package dev.hookrelay.server.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    boolean existsByDeliveryIdAndPublishedAtIsNull(java.util.UUID deliveryId);
}
