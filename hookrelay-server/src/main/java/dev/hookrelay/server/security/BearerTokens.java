package dev.hookrelay.server.security;

import jakarta.servlet.http.HttpServletRequest;

final class BearerTokens {

    private BearerTokens() { }

    static String from(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).strip();
    }
}
