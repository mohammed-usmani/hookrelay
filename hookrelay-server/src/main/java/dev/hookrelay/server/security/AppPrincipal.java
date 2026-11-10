package dev.hookrelay.server.security;

import java.util.UUID;

/** Who is calling: the application, and which of its API keys was used. */
public record AppPrincipal(UUID applicationId, UUID apiKeyId) { }
