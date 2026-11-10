package dev.hookrelay.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates operators by the static admin token (constant-time comparison). */
class AdminTokenFilter extends OncePerRequestFilter {

    private final byte[] adminToken;

    AdminTokenFilter(String adminToken) {
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = BearerTokens.from(request);
        if (token != null && MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "admin", null, List.of(new SimpleGrantedAuthority("ADMIN"))));
        }
        chain.doFilter(request, response);
    }
}
