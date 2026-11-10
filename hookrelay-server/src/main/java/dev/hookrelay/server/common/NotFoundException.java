package dev.hookrelay.server.common;

public class NotFoundException extends ApiException {

    public NotFoundException(String what) {
        super(404, "not-found", what + " not found");
    }
}
