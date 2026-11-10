package dev.hookrelay.server.delivery;

/** What happened on one HTTP attempt. {@code status} is null when no response arrived. */
public record SendResult(Outcome outcome, Integer status, long durationMs, String bodySnippet, String error) {

    public enum Outcome { SUCCESS, HTTP_ERROR, REDIRECT, TIMEOUT, CONNECTION_ERROR, TLS_ERROR, SSRF_BLOCKED }

    public boolean succeeded() {
        return outcome == Outcome.SUCCESS;
    }
}
