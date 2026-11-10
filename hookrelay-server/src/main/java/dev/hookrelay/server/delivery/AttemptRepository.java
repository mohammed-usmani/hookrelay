package dev.hookrelay.server.delivery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    List<Attempt> findByDeliveryIdOrderByAttemptNo(UUID deliveryId);

    /** Attempt numbers keep counting across replays (attempt_count restarts, history doesn't). */
    @Query("select coalesce(max(a.attemptNo), 0) from Attempt a where a.deliveryId = :deliveryId")
    int lastAttemptNo(UUID deliveryId);
}
