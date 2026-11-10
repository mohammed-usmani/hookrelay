package dev.hookrelay.server.event;

import org.springframework.data.jpa.repository.JpaRepository;

interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {
}
