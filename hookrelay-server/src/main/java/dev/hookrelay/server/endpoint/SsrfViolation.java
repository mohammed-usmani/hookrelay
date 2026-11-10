package dev.hookrelay.server.endpoint;

/** A URL or address HookRelay refuses to call. */
public class SsrfViolation extends RuntimeException {

    public SsrfViolation(String reason) {
        super(reason);
    }
}
