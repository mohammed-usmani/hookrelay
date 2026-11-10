package dev.hookrelay.server.delivery;

import java.util.List;
import java.util.UUID;

/** Everything needed to make one attempt, taken from the DB under lock. */
public record Claim(UUID deliveryId, UUID token, int attemptNo, UUID applicationId, UUID eventId,
                    String eventType, UUID endpointId, String url, String payload, List<String> secrets,
                    /* a reserved rate-limit slot: send no earlier than this from now */ java.time.Duration sendAfter) { }
