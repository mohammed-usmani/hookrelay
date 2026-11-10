package dev.hookrelay.server.notify;

import dev.hookrelay.server.config.RabbitTopology;
import dev.hookrelay.server.delivery.Delivery;
import dev.hookrelay.server.delivery.DeliveryRepository;
import dev.hookrelay.server.delivery.DeliveryStatus;
import dev.hookrelay.server.event.Event;
import dev.hookrelay.server.event.EventRepository;
import dev.hookrelay.server.eventtype.SystemEventTypes;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the dead-letter queue: counts every dead delivery and emits a
 * {@code hookrelay.delivery.dead} event for the application. The database status stays the
 * source of truth; this queue only drives side effects, so it always drains.
 */
@Component
class DeadLetterConsumer {

    /** Reasons already covered by another notification (or chosen by the app itself). */
    private static final Set<String> QUIET_REASONS = Set.of("endpoint_disabled", "endpoint_deleted");

    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final SystemEvents systemEvents;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    DeadLetterConsumer(DeliveryRepository deliveries, EventRepository events, SystemEvents systemEvents,
                       MeterRegistry meters, ObjectMapper json) {
        this.deliveries = deliveries;
        this.events = events;
        this.systemEvents = systemEvents;
        this.meters = meters;
        this.json = json;
    }

    @RabbitListener(id = "deadLetterConsumer", queues = RabbitTopology.DEAD_QUEUE)
    @Transactional
    public void onDead(Message message) {
        JsonNode body = json.readTree(message.getBody());
        UUID deliveryId = UUID.fromString(body.at("/deliveryId").asString());
        String token = body.at("/dispatchToken").asString();
        Optional<Delivery> found = deliveries.findById(deliveryId);
        if (found.isEmpty() || found.get().getStatus() != DeliveryStatus.DEAD) {
            return; // replayed since; nothing to report
        }
        Delivery d = found.get();
        meters.counter("hookrelay.deliveries.dead", "reason", d.getDeadReason()).increment();
        if (QUIET_REASONS.contains(d.getDeadReason())) {
            return;
        }
        Event event = events.findById(d.getEventId()).orElseThrow();
        if (SystemEventTypes.isSystem(event.getEventType())) {
            return; // a failed notification never notifies again: no loops
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", d.getId().toString());
        data.put("eventId", d.getEventId().toString());
        data.put("endpointId", d.getEndpointId().toString());
        data.put("eventType", event.getEventType());
        data.put("reason", d.getDeadReason());
        data.put("attempts", d.getAttemptCount());
        systemEvents.emit(d.getApplicationId(), SystemEventTypes.DELIVERY_DEAD, data, "dead:" + d.getId() + ":" + token);
    }
}
