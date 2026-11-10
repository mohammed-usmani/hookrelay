package dev.hookrelay.server.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The application making the current request. Every app-scoped query filters by this. */
public final class TenantContext {

    private TenantContext() { }

    public static UUID currentAppId() {
        return current().applicationId();
    }

    public static AppPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal p) {
            return p;
        }
        throw new IllegalStateException("No application in security context");
    }
}
