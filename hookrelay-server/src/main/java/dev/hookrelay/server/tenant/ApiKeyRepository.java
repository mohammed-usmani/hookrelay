package dev.hookrelay.server.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByPrefix(String prefix);

    Optional<ApiKey> findByIdAndApplicationId(UUID id, UUID applicationId);

    List<ApiKey> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);

    @Modifying
    @Query("""
            update ApiKey k set k.lastUsedAt = :now
            where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :staleBefore)""")
    int touch(UUID id, Instant now, Instant staleBefore);
}
