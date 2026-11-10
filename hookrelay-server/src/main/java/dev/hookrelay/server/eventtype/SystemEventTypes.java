package dev.hookrelay.server.eventtype;

import java.util.List;

/** Event types HookRelay itself emits into each application's stream. */
public final class SystemEventTypes {

    public static final String ENDPOINT_DISABLED = "hookrelay.endpoint.disabled";
    public static final String DELIVERY_DEAD = "hookrelay.delivery.dead";
    public static final String TEST = "hookrelay.test";

    public static final List<String> NAMES = List.of(ENDPOINT_DISABLED, DELIVERY_DEAD, TEST);

    private SystemEventTypes() { }

    public static boolean isSystem(String name) {
        return name.startsWith(EventType.SYSTEM_PREFIX);
    }
}
