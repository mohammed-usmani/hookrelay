package dev.hookrelay.server.endpoint;

/** ACTIVE: receiving. PAUSED: by the application. DISABLED: by HookRelay after failing too long. */
public enum EndpointStatus { ACTIVE, PAUSED, DISABLED }
