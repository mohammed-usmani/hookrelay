CREATE TABLE events (
    id                UUID         NOT NULL PRIMARY KEY,
    application_id    UUID         NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    payload           JSON         NULL,
    payload_purged_at DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    CONSTRAINT fk_events_app FOREIGN KEY (application_id) REFERENCES applications (id),
    INDEX idx_events_list (application_id, created_at, id),
    INDEX idx_events_created (created_at)
);

CREATE TABLE idempotency_keys (
    application_id UUID         NOT NULL,
    idem_key       VARCHAR(255) NOT NULL,
    body_sha256    BINARY(32)   NOT NULL,
    event_id       UUID         NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (application_id, idem_key),
    INDEX idx_idempotency_created (created_at)
);

CREATE TABLE deliveries (
    id             UUID         NOT NULL PRIMARY KEY,
    application_id UUID         NOT NULL,
    event_id       UUID         NOT NULL,
    endpoint_id    UUID         NOT NULL,
    status         ENUM('PENDING', 'FAILED_RETRYING', 'SUCCEEDED', 'DEAD') NOT NULL,
    dead_reason    VARCHAR(40)  NULL,
    attempt_count  INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    deadline_at    DATETIME(6)  NOT NULL,
    dispatch_token UUID         NOT NULL,
    parked_at      DATETIME(6)  NULL,
    last_error     VARCHAR(500) NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    CONSTRAINT fk_deliveries_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_deliveries_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoints (id),
    INDEX idx_deliveries_endpoint_status (endpoint_id, status),
    INDEX idx_deliveries_due (status, next_attempt_at),
    INDEX idx_deliveries_list (application_id, created_at, id),
    INDEX idx_deliveries_event (event_id)
);

CREATE TABLE outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    delivery_id    UUID         NOT NULL,
    dispatch_token UUID         NOT NULL,
    target         ENUM('WORK', 'DEAD') NOT NULL DEFAULT 'WORK',
    delay_tier     VARCHAR(8)   NULL,
    created_at     DATETIME(6)  NOT NULL,
    published_at   DATETIME(6)  NULL,
    INDEX idx_outbox_pending (published_at, id),
    INDEX idx_outbox_delivery (delivery_id)
);
