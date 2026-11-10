package dev.hookrelay.server.notify;

import dev.hookrelay.server.event.IngestService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Emits HookRelay's own events into an application's stream; apps subscribe like any other type. */
@Component
public class SystemEvents {

    private final IngestService ingest;
    private final ObjectMapper json;

    SystemEvents(IngestService ingest, ObjectMapper json) {
        this.ingest = ingest;
        this.json = json;
    }

    /** {@code dedupeKey} makes repeated triggers for the same fact produce one event. */
    public UUID emit(UUID appId, String type, Map<String, Object> data, String dedupeKey) {
        return ingest.ingestSystem(appId, type, json.writeValueAsString(data), dedupeKey);
    }
}
