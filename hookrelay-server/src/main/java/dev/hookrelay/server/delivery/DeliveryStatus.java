package dev.hookrelay.server.delivery;

public enum DeliveryStatus {
    PENDING, FAILED_RETRYING, SUCCEEDED, DEAD;

    public boolean isFinal() {
        return this == SUCCEEDED || this == DEAD;
    }
}
