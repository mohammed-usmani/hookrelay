CREATE TABLE attempts (
    id               UUID         NOT NULL PRIMARY KEY,
    delivery_id      UUID         NOT NULL,
    endpoint_id      UUID         NOT NULL,
    attempt_no       INT          NOT NULL,
    started_at       DATETIME(6)  NOT NULL,
    duration_ms      BIGINT       NOT NULL,
    response_status  INT          NULL,
    response_snippet TEXT         NULL,
    outcome          VARCHAR(20)  NOT NULL,
    error            VARCHAR(500) NULL,
    INDEX idx_attempts_delivery (delivery_id, attempt_no),
    INDEX idx_attempts_endpoint (endpoint_id, started_at),
    INDEX idx_attempts_started (started_at)
);
