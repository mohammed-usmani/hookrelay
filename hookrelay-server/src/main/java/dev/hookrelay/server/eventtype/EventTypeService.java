package dev.hookrelay.server.eventtype;

import dev.hookrelay.server.common.ApiException;
import dev.hookrelay.server.common.ConflictException;
import dev.hookrelay.server.common.Cursor;
import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.common.Ids;
import dev.hookrelay.server.common.NotFoundException;
import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.tenant.ApplicationCreated;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventTypeService {

    private static final java.util.Map<String, String> SYSTEM_DESCRIPTIONS = java.util.Map.of(
            SystemEventTypes.ENDPOINT_DISABLED, "An endpoint was disabled after failing for too long",
            SystemEventTypes.DELIVERY_DEAD, "A delivery used up its retries and moved to the dead-letter queue",
            SystemEventTypes.TEST, "Test event sent from the API");

    private final EventTypeRepository repo;
    private final SchemaValidator schemas;
    private final Clock clock;

    EventTypeService(EventTypeRepository repo, SchemaValidator schemas, Clock clock) {
        this.repo = repo;
        this.schemas = schemas;
        this.clock = clock;
    }

    @EventListener
    void onApplicationCreated(ApplicationCreated e) {
        for (String name : SystemEventTypes.NAMES) {
            repo.save(new EventType(Ids.newId(), e.applicationId(), name, SYSTEM_DESCRIPTIONS.get(name), null,
                    true, Times.now(clock)));
        }
    }

    @Transactional
    public EventType create(UUID appId, String name, String description, String schemaJson) {
        if (SystemEventTypes.isSystem(name)) {
            throw ApiException.unprocessable("reserved-event-type", "the 'hookrelay.' prefix is reserved");
        }
        if (repo.existsByApplicationIdAndName(appId, name)) {
            throw new ConflictException("duplicate-event-type", "event type '" + name + "' already exists");
        }
        if (schemaJson != null) {
            schemas.checkSchema(schemaJson);
        }
        return repo.save(new EventType(Ids.newId(), appId, name, description, schemaJson, false, Times.now(clock)));
    }

    @Transactional(readOnly = true)
    public EventType get(UUID appId, String name) {
        return repo.findByApplicationIdAndName(appId, name).orElseThrow(() -> new NotFoundException("event type"));
    }

    @Transactional
    public EventType update(UUID appId, String name, String description, String schemaJson) {
        EventType t = editable(appId, name);
        if (schemaJson != null) {
            schemas.checkSchema(schemaJson);
        }
        t.update(description, schemaJson, Times.now(clock));
        return t;
    }

    @Transactional
    public EventType archive(UUID appId, String name) {
        EventType t = editable(appId, name);
        t.archive(Times.now(clock));
        return t;
    }

    @Transactional
    public EventType unarchive(UUID appId, String name) {
        EventType t = editable(appId, name);
        t.unarchive(Times.now(clock));
        return t;
    }

    @Transactional(readOnly = true)
    public CursorPage<EventType> list(UUID appId, String cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        Cursor.Position after = cursor == null ? null : Cursor.decode(cursor);
        List<EventType> rows = repo.page(appId, after == null ? null : after.createdAt(),
                after == null ? null : after.id(), Limit.of(n + 1));
        return CursorPage.of(rows, n, EventType::getCreatedAt, EventType::getId, t -> t);
    }

    /** The type an application may post events of; 422 otherwise. */
    @Transactional(readOnly = true)
    public EventType requirePostable(UUID appId, String name) {
        if (SystemEventTypes.isSystem(name)) {
            throw ApiException.unprocessable("reserved-event-type", "system event types cannot be posted");
        }
        EventType t = repo.findByApplicationIdAndName(appId, name).orElseThrow(() ->
                ApiException.unprocessable("unknown-event-type", "event type '" + name + "' does not exist"));
        if (t.isArchived()) {
            throw ApiException.unprocessable("archived-event-type", "event type '" + name + "' is archived");
        }
        return t;
    }

    /** True if every name is a type of this application. */
    @Transactional(readOnly = true)
    public boolean allExist(UUID appId, Collection<String> names) {
        return names.isEmpty() || repo.countByApplicationIdAndNameIn(appId, names) == names.stream().distinct().count();
    }

    private EventType editable(UUID appId, String name) {
        EventType t = get(appId, name);
        if (t.isSystem()) {
            throw new ConflictException("system-event-type", "system event types cannot be changed");
        }
        return t;
    }
}
