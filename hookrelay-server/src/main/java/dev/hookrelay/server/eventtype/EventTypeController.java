package dev.hookrelay.server.eventtype;

import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/event-types")
class EventTypeController {

    static final String NAME_REGEX = "^[a-z0-9]+(\\.[a-z0-9_]+)+$";

    record CreateEventType(
            @NotBlank @Size(max = 100) @Pattern(regexp = NAME_REGEX, message = "must look like 'order.created'") String name,
            @Size(max = 500) String description,
            JsonNode schema) { }

    record UpdateEventType(@Size(max = 500) String description, JsonNode schema) { }

    record EventTypeView(String name, String description, JsonNode schema, boolean system, boolean archived,
                         Instant createdAt, Instant updatedAt) { }

    private final EventTypeService service;
    private final ObjectMapper json;

    EventTypeController(EventTypeService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EventTypeView create(@Valid @RequestBody CreateEventType body) {
        return view(service.create(TenantContext.currentAppId(), body.name(), body.description(), text(body.schema())));
    }

    @GetMapping
    CursorPage<EventTypeView> list(@RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit) {
        CursorPage<EventType> page = service.list(TenantContext.currentAppId(), cursor, limit);
        return new CursorPage<>(page.data().stream().map(this::view).toList(), page.nextCursor());
    }

    @GetMapping("/{name}")
    EventTypeView get(@PathVariable String name) {
        return view(service.get(TenantContext.currentAppId(), name));
    }

    @PatchMapping("/{name}")
    EventTypeView update(@PathVariable String name, @Valid @RequestBody UpdateEventType body) {
        return view(service.update(TenantContext.currentAppId(), name, body.description(), text(body.schema())));
    }

    @PostMapping("/{name}/archive")
    EventTypeView archive(@PathVariable String name) {
        return view(service.archive(TenantContext.currentAppId(), name));
    }

    @PostMapping("/{name}/unarchive")
    EventTypeView unarchive(@PathVariable String name) {
        return view(service.unarchive(TenantContext.currentAppId(), name));
    }

    private static String text(JsonNode schema) {
        return schema == null || schema.isNull() ? null : schema.toString();
    }

    private EventTypeView view(EventType t) {
        JsonNode schema = t.getJsonSchema() == null ? null : json.readTree(t.getJsonSchema());
        return new EventTypeView(t.getName(), t.getDescription(), schema, t.isSystem(), t.isArchived(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
