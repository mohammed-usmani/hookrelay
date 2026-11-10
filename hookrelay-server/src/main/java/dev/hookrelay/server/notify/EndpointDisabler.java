package dev.hookrelay.server.notify;

import dev.hookrelay.server.config.HookRelayProperties;
import dev.hookrelay.server.endpoint.Endpoint;
import dev.hookrelay.server.endpoint.EndpointStatus;
import dev.hookrelay.server.eventtype.SystemEventTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Turns off an endpoint that has not succeeded for too long, and tells the application. */
@Component
public class EndpointDisabler {

    private final SystemEvents systemEvents;
    private final Duration disableAfter;

    EndpointDisabler(SystemEvents systemEvents, HookRelayProperties props) {
        this.systemEvents = systemEvents;
        this.disableAfter = props.circuit().disableAfter();
    }

    /**
     * Called after a failed attempt, with the endpoint row locked. Returns true if the endpoint
     * was disabled just now (the caller then dead-letters its pending deliveries).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean check(Endpoint e, Instant now) {
        Instant firstFailure = e.circuit().firstFailureAt();
        if (e.getStatus() != EndpointStatus.ACTIVE || firstFailure == null
                || firstFailure.isAfter(now.minus(disableAfter))) {
            return false;
        }
        e.setStatus(EndpointStatus.DISABLED, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("endpointId", e.getId().toString());
        data.put("url", e.getUrl());
        data.put("reason", "no_success_" + disableAfter.toDays() + "_days");
        data.put("firstFailureAt", firstFailure.toString());
        systemEvents.emit(e.getApplicationId(), SystemEventTypes.ENDPOINT_DISABLED, data,
                "disabled:" + e.getId() + ":" + firstFailure);
        return true;
    }
}
