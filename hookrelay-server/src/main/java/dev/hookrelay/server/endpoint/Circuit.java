package dev.hookrelay.server.endpoint;

import java.time.Instant;

/** An endpoint's circuit breaker state, as a value. */
public record Circuit(CircuitState state, int failures, Instant openUntil, int level, Instant lastSuccessAt,
                      Instant firstFailureAt) {

    public static final Circuit CLOSED = new Circuit(CircuitState.CLOSED, 0, null, 0, null, null);
}
