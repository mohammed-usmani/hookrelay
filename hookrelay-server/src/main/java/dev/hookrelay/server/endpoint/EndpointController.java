package dev.hookrelay.server.endpoint;

import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/endpoints")
class EndpointController {

    record CreateEndpoint(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 500) String description,
            Set<@NotBlank String> eventTypes,
            @Min(1) @Max(10_000) Integer rateLimitPerSec) { }

    /** Omitted fields stay unchanged; {@code rateLimitPerSec: 0} removes the limit. */
    record UpdateEndpoint(
            @Size(max = 2048) String url,
            @Size(max = 500) String description,
            Set<@NotBlank String> eventTypes,
            @Min(0) @Max(10_000) Integer rateLimitPerSec) { }

    record EndpointView(UUID id, String url, String description, Set<String> eventTypes, EndpointStatus status,
                        Integer rateLimitPerSec, CircuitState circuitState, Instant createdAt, Instant updatedAt) {
        static EndpointView of(Endpoint e) {
            return new EndpointView(e.getId(), e.getUrl(), e.getDescription(), Set.copyOf(e.getEventTypes()),
                    e.getStatus(), e.getRateLimitPerSec(), e.getCircuitState(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    record CreatedEndpointView(UUID id, String url, String description, Set<String> eventTypes,
                               EndpointStatus status, Integer rateLimitPerSec, String secret, Instant createdAt) { }

    record SecretView(String secret) { }

    private final EndpointService service;

    EndpointController(EndpointService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedEndpointView create(@Valid @RequestBody CreateEndpoint body) {
        EndpointService.Created c = service.create(TenantContext.currentAppId(), body.url(), body.description(),
                body.eventTypes() == null ? Set.of() : body.eventTypes(), body.rateLimitPerSec());
        Endpoint e = c.endpoint();
        return new CreatedEndpointView(e.getId(), e.getUrl(), e.getDescription(), Set.copyOf(e.getEventTypes()),
                e.getStatus(), e.getRateLimitPerSec(), c.secret(), e.getCreatedAt());
    }

    @GetMapping
    CursorPage<EndpointView> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        CursorPage<Endpoint> page = service.list(TenantContext.currentAppId(), cursor, limit);
        return new CursorPage<>(page.data().stream().map(EndpointView::of).toList(), page.nextCursor());
    }

    @GetMapping("/{id}")
    EndpointView get(@PathVariable UUID id) {
        return EndpointView.of(service.get(TenantContext.currentAppId(), id));
    }

    @PatchMapping("/{id}")
    EndpointView update(@PathVariable UUID id, @Valid @RequestBody UpdateEndpoint body) {
        return EndpointView.of(service.update(TenantContext.currentAppId(), id, body.url(), body.description(),
                body.eventTypes(), body.rateLimitPerSec()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(TenantContext.currentAppId(), id);
    }

    @PostMapping("/{id}/pause")
    EndpointView pause(@PathVariable UUID id) {
        return EndpointView.of(service.pause(TenantContext.currentAppId(), id));
    }

    @PostMapping("/{id}/resume")
    EndpointView resume(@PathVariable UUID id) {
        return EndpointView.of(service.resume(TenantContext.currentAppId(), id));
    }

    @PostMapping("/{id}/rotate-secret")
    SecretView rotateSecret(@PathVariable UUID id) {
        return new SecretView(service.rotateSecret(TenantContext.currentAppId(), id));
    }

    @GetMapping("/{id}/secret")
    SecretView secret(@PathVariable UUID id) {
        return new SecretView(service.currentSecret(TenantContext.currentAppId(), id));
    }
}
