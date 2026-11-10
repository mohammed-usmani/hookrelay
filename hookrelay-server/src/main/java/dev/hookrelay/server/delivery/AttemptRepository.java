package dev.hookrelay.server.delivery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    List<Attempt> findByDeliveryIdOrderByAttemptNo(UUID deliveryId);
}
