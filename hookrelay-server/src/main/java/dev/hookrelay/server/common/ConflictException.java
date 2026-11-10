package dev.hookrelay.server.common;

public class ConflictException extends ApiException {

    public ConflictException(String slug, String detail) {
        super(409, slug, detail);
    }
}
