package dev.hookrelay.server.delivery;

import dev.hookrelay.server.common.Cursor;
import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.common.NotFoundException;
import dev.hookrelay.server.event.EventRepository;
import dev.hookrelay.server.security.TenantContext;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DeliveryController {

    record ReplaySince(@NotNull Instant since) { }

    record AttemptView(UUID id, int attemptNo, Instant startedAt, long durationMs, Integer responseStatus,
                       String responseSnippet, SendResult.Outcome outcome, String error) {
        static AttemptView of(Attempt a) {
            return new AttemptView(a.getId(), a.getAttemptNo(), a.getStartedAt(), a.getDurationMs(),
                    a.getResponseStatus(), a.getResponseSnippet(), a.getOutcome(), a.getError());
        }
    }

    private final ReplayService replays;
    private final DeliveryRepository deliveries;
    private final AttemptRepository attempts;
    private final EventRepository events;

    DeliveryController(ReplayService replays, DeliveryRepository deliveries, AttemptRepository attempts,
                       EventRepository events) {
        this.replays = replays;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.events = events;
    }

    @GetMapping("/v1/deliveries")
    CursorPage<DeliveryView> list(@RequestParam(required = false) DeliveryStatus status,
                                  @RequestParam(required = false) UUID endpointId,
                                  @RequestParam(required = false) UUID eventId,
                                  @RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        int n = CursorPage.clampLimit(limit);
        Cursor.Position after = cursor == null ? null : Cursor.decode(cursor);
        List<Delivery> rows = deliveries.page(TenantContext.currentAppId(), status, endpointId, eventId, from, to,
                after == null ? null : after.createdAt(), after == null ? null : after.id(), Limit.of(n + 1));
        return CursorPage.of(rows, n, Delivery::getCreatedAt, Delivery::getId, DeliveryView::of);
    }

    @GetMapping("/v1/deliveries/{id}")
    DeliveryView get(@PathVariable UUID id) {
        return DeliveryView.of(owned(id));
    }

    /** All attempts, oldest first; bounded by the retry schedule times the number of replays. */
    @GetMapping("/v1/deliveries/{id}/attempts")
    CursorPage<AttemptView> attempts(@PathVariable UUID id) {
        owned(id);
        return new CursorPage<>(attempts.findByDeliveryIdOrderByAttemptNo(id).stream().map(AttemptView::of).toList(),
                null);
    }

    /** One delivery per subscribed endpoint, so never large enough to need paging. */
    @GetMapping("/v1/events/{id}/deliveries")
    CursorPage<DeliveryView> forEvent(@PathVariable UUID id) {
        UUID appId = TenantContext.currentAppId();
        events.findByIdAndApplicationId(id, appId).orElseThrow(() -> new NotFoundException("event"));
        return new CursorPage<>(deliveries.findByEventIdAndApplicationIdOrderByCreatedAt(id, appId).stream()
                .map(DeliveryView::of).toList(), null);
    }

    private Delivery owned(UUID id) {
        return deliveries.findByIdAndApplicationId(id, TenantContext.currentAppId())
                .orElseThrow(() -> new NotFoundException("delivery"));
    }

    @PostMapping("/v1/deliveries/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeliveryView replay(@PathVariable UUID id) {
        return DeliveryView.of(replays.replay(TenantContext.currentAppId(), id));
    }

    @PostMapping("/v1/endpoints/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReplayService.BulkReplayResult replayEndpoint(@PathVariable UUID id, @Valid @RequestBody ReplaySince body) {
        return replays.replayEndpoint(TenantContext.currentAppId(), id, body.since());
    }
}
