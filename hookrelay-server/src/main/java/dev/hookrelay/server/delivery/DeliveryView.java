package dev.hookrelay.server.delivery;

import java.time.Instant;
import java.util.UUID;

/** A delivery as the API shows it. */
public record DeliveryView(UUID id, UUID eventId, UUID endpointId, DeliveryStatus status, String deadReason,
                           int attemptCount, Instant nextAttemptAt, Instant deadlineAt, String lastError,
                           Instant createdAt, Instant updatedAt) {

    public static DeliveryView of(Delivery d) {
        return new DeliveryView(d.getId(), d.getEventId(), d.getEndpointId(), d.getStatus(), d.getDeadReason(),
                d.getAttemptCount(), d.getStatus().isFinal() ? null : d.getNextAttemptAt(), d.getDeadlineAt(),
                d.getLastError(), d.getCreatedAt(), d.getUpdatedAt());
    }
}
