package dev.hookrelay.server.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All HookRelay settings, bound from {@code hookrelay.*} (env: {@code HOOKRELAY_*}). */
@Validated
@ConfigurationProperties("hookrelay")
public record HookRelayProperties(
        @NotBlank String adminToken,
        @NotBlank String encryptionKey) {
}
