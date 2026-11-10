package dev.hookrelay.server.endpoint;

import java.util.UUID;

/** Published (after commit) when an endpoint's deliveries need attention. */
public sealed interface EndpointLifecycle {
    UUID endpointId();

    record Resumed(UUID endpointId) implements EndpointLifecycle { }

    record Deleted(UUID endpointId) implements EndpointLifecycle { }
}
