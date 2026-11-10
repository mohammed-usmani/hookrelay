package dev.hookrelay.server.event;

import dev.hookrelay.server.common.ApiException;
import dev.hookrelay.server.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import dev.hookrelay.server.common.Cursor;
import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.common.NotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
class EventController {

    /** Payload limit plus room for the envelope. */
    static final int MAX_REQUEST_BYTES = EventRequestParser.MAX_PAYLOAD_BYTES + 65_536;

    record Accepted(UUID id, long deliveryCount) { }

    /** {@code payload} is the stored JSON as-is; null once retention purged it (or in lists). */
    record EventView(UUID id, String eventType,
                     @com.fasterxml.jackson.annotation.JsonRawValue String payload,
                     boolean payloadPurged, Instant createdAt) {
        static EventView of(Event e, boolean withPayload) {
            return new EventView(e.getId(), e.getEventType(), withPayload ? e.getPayload() : null,
                    e.isPayloadPurged(), e.getCreatedAt());
        }
    }

    private final IngestService ingest;
    private final EventRepository events;

    EventController(IngestService ingest, EventRepository events) {
        this.ingest = ingest;
        this.events = events;
    }

    @GetMapping
    CursorPage<EventView> list(@RequestParam(required = false) String eventType,
                               @RequestParam(required = false) Instant from,
                               @RequestParam(required = false) Instant to,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit) {
        int n = CursorPage.clampLimit(limit);
        Cursor.Position after = cursor == null ? null : Cursor.decode(cursor);
        List<Event> rows = events.page(TenantContext.currentAppId(), eventType, from, to,
                after == null ? null : after.createdAt(), after == null ? null : after.id(), Limit.of(n + 1));
        return CursorPage.of(rows, n, Event::getCreatedAt, Event::getId, e -> EventView.of(e, false));
    }

    @GetMapping("/{id}")
    EventView get(@PathVariable UUID id) {
        Event e = events.findByIdAndApplicationId(id, TenantContext.currentAppId())
                .orElseThrow(() -> new NotFoundException("event"));
        return EventView.of(e, !e.isPayloadPurged());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Accepted> create(HttpServletRequest request,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idemKey)
            throws IOException {
        IngestService.IngestResult r = ingest.ingest(TenantContext.currentAppId(), readBody(request), idemKey);
        ResponseEntity.BodyBuilder res = ResponseEntity.accepted();
        if (r.replayed()) {
            res.header("Idempotent-Replayed", "true");
        }
        return res.body(new Accepted(r.eventId(), r.deliveryCount()));
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw tooLarge();
            }
            return body;
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(413, "payload-too-large", "request body is too large");
    }
}
