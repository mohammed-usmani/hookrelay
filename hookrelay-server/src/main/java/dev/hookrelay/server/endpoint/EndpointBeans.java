package dev.hookrelay.server.endpoint;

import dev.hookrelay.server.config.HookRelayProperties;
import java.net.InetAddress;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class EndpointBeans {

    @Bean
    HostResolver hostResolver() {
        return host -> List.of(InetAddress.getAllByName(host));
    }

    @Bean
    SsrfGuard ssrfGuard(HookRelayProperties props, HostResolver resolver) {
        return new SsrfGuard(props.ssrf(), resolver);
    }
}
