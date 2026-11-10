package dev.hookrelay.server.security;

import dev.hookrelay.server.tenant.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates applications by API key and puts an {@link AppPrincipal} in the context. */
class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService keys;

    ApiKeyAuthFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        keys.authenticateKey(BearerTokens.from(request)).ifPresent(k ->
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new AppPrincipal(k.applicationId(), k.keyId()), null,
                        List.of(new SimpleGrantedAuthority("APP")))));
        chain.doFilter(request, response);
    }
}
