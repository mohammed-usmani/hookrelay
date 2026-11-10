package dev.hookrelay.server.notify;

import dev.hookrelay.server.endpoint.Endpoint;
import dev.hookrelay.server.endpoint.EndpointService;
import dev.hookrelay.server.event.IngestService;
import dev.hookrelay.server.eventtype.SystemEventTypes;
import dev.hookrelay.server.security.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** "Send test event": a {@code hookrelay.test} event to exactly one endpoint. */
@RestController
class TestSendController {

    record TestSent(UUID eventId, UUID deliveryId) { }

    private final EndpointService endpoints;
    private final IngestService ingest;
    private final ObjectMapper json;

    TestSendController(EndpointService endpoints, IngestService ingest, ObjectMapper json) {
        this.endpoints = endpoints;
        this.ingest = ingest;
        this.json = json;
    }

    @PostMapping("/v1/endpoints/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TestSent send(@PathVariable UUID id) {
        UUID appId = TenantContext.currentAppId();
        Endpoint e = endpoints.get(appId, id);
        String payload = json.writeValueAsString(Map.of(
                "message", "Test event from HookRelay", "endpointId", e.getId().toString()));
        IngestService.Targeted t = ingest.ingestTargeted(appId, SystemEventTypes.TEST, payload, e);
        return new TestSent(t.eventId(), t.deliveryId());
    }
}
