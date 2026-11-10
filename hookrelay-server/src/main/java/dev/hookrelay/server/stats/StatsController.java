package dev.hookrelay.server.stats;

import dev.hookrelay.server.security.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

    private final EndpointStatsService stats;

    StatsController(EndpointStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/v1/endpoints/{id}/stats")
    EndpointStatsService.EndpointStats stats(@PathVariable UUID id) {
        return stats.stats(TenantContext.currentAppId(), id);
    }
}
