package dev.hookrelay.server.security;

import dev.hookrelay.server.common.ProblemHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Writes problem JSON from filters, where the MVC exception handler can't reach. */
final class ProblemWriter {

    private ProblemWriter() { }

    static void write(HttpServletResponse response, int status, String slug, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"%s%s","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(ProblemHandler.TYPE_BASE, slug, title, status, detail));
    }
}
