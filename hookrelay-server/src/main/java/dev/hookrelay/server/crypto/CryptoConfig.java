package dev.hookrelay.server.crypto;

import dev.hookrelay.server.config.HookRelayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CryptoConfig {

    @Bean
    SecretCipher secretCipher(HookRelayProperties props) {
        return new SecretCipher(props.encryptionKey());
    }
}
