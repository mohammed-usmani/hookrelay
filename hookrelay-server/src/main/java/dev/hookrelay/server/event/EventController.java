package dev.hookrelay.server.event;

import dev.hookrelay.server.common.ApiException;
import dev.hookrelay.server.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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

    private final IngestService ingest;

    EventController(IngestService ingest) {
        this.ingest = ingest;
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
