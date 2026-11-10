package dev.hookrelay.server.tenant;

import java.util.UUID;

/** Published (in-process) when an application is created, inside the creating transaction. */
public record ApplicationCreated(UUID applicationId) { }
