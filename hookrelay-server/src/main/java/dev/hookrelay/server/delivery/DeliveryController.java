package dev.hookrelay.server.delivery;

import dev.hookrelay.server.security.TenantContext;
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

    private final ReplayService replays;

    DeliveryController(ReplayService replays) {
        this.replays = replays;
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
