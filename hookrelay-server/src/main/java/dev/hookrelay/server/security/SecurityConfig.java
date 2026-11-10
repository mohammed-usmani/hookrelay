package dev.hookrelay.server.security;

import dev.hookrelay.server.config.HookRelayProperties;
import dev.hookrelay.server.tenant.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Two credentials, two route trees: the admin token only works on {@code /v1/admin/**},
 * API keys only on the rest of {@code /v1/**}. Everything is stateless.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain adminChain(HttpSecurity http, HookRelayProperties props) throws Exception {
        common(http.securityMatcher("/v1/admin/**"))
                .addFilterBefore(new AdminTokenFilter(props.adminToken()), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("ADMIN"));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain appChain(HttpSecurity http, ApiKeyService keys) throws Exception {
        common(http.securityMatcher("/v1/**"))
                .addFilterBefore(new ApiKeyAuthFilter(keys), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("APP"));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        common(http).authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error")
                .permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    private static HttpSecurity common(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemWriter.write(res, 401, "unauthorized",
                                "Unauthorized", "Missing or invalid credentials"))
                        .accessDeniedHandler((req, res, ex) -> ProblemWriter.write(res, 403, "forbidden",
                                "Forbidden", "Not allowed")));
    }
}
