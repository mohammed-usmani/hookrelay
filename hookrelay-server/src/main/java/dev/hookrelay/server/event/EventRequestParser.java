package dev.hookrelay.server.event;

import dev.hookrelay.server.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads {@code {"eventType": "...", "payload": {...}}} with a streaming parser and slices the
 * payload out of the original bytes, instead of re-serializing it. Whitespace, key order and
 * number formatting survive exactly, so the signature covers precisely what the sender sent.
 */
@Component
class EventRequestParser {

    static final int MAX_PAYLOAD_BYTES = 262_144;

    record Parsed(String eventType, String payload) { }

    private final ObjectMapper json;

    EventRequestParser(ObjectMapper json) {
        this.json = json;
    }

    Parsed parse(byte[] body) {
        String eventType = null;
        String payload = null;
        try (JsonParser p = json.createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw bad("request body must be a JSON object");
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if ("eventType".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) {
                        throw bad("eventType must be a string");
                    }
                    eventType = p.getString();
                } else if ("payload".equals(field)) {
                    if (value != JsonToken.START_OBJECT) {
                        throw bad("payload must be a JSON object");
                    }
                    int start = (int) p.currentTokenLocation().getByteOffset();
                    p.skipChildren();
                    int end = (int) p.currentTokenLocation().getByteOffset() + 1;
                    if (end - start > MAX_PAYLOAD_BYTES) {
                        throw new ApiException(413, "payload-too-large",
                                "payload is larger than " + MAX_PAYLOAD_BYTES + " bytes");
                    }
                    payload = new String(body, start, end - start, StandardCharsets.UTF_8);
                } else {
                    p.skipChildren();
                }
            }
            if (p.nextToken() != null) {
                throw bad("unexpected content after the JSON object");
            }
        } catch (JacksonException e) {
            throw bad("request body is not valid JSON");
        }
        if (eventType == null || eventType.isBlank()) {
            throw bad("eventType is required");
        }
        if (payload == null) {
            throw bad("payload is required");
        }
        return new Parsed(eventType, payload);
    }

    private static ApiException bad(String detail) {
        return ApiException.badRequest("malformed-request", detail);
    }
}
