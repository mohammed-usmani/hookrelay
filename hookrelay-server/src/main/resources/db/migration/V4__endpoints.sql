CREATE TABLE endpoints (
    id                   UUID          NOT NULL PRIMARY KEY,
    application_id       UUID          NOT NULL,
    url                  VARCHAR(2048) NOT NULL,
    description          VARCHAR(500)  NULL,
    status               ENUM('ACTIVE', 'PAUSED', 'DISABLED') NOT NULL,
    rate_limit_per_sec   INT           NULL,
    circuit_state        ENUM('CLOSED', 'OPEN', 'HALF_OPEN') NOT NULL DEFAULT 'CLOSED',
    consecutive_failures INT           NOT NULL DEFAULT 0,
    circuit_open_until   DATETIME(6)   NULL,
    circuit_level        TINYINT       NOT NULL DEFAULT 0,
    last_success_at      DATETIME(6)   NULL,
    first_failure_at     DATETIME(6)   NULL,
    deleted_at           DATETIME(6)   NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    CONSTRAINT fk_endpoints_app FOREIGN KEY (application_id) REFERENCES applications (id),
    INDEX idx_endpoints_list (application_id, created_at, id)
);

CREATE TABLE endpoint_event_types (
    endpoint_id     UUID         NOT NULL,
    event_type_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (endpoint_id, event_type_name),
    CONSTRAINT fk_endpoint_event_types FOREIGN KEY (endpoint_id) REFERENCES endpoints (id)
);

CREATE TABLE endpoint_secrets (
    id          UUID           NOT NULL PRIMARY KEY,
    endpoint_id UUID           NOT NULL,
    secret_enc  VARBINARY(255) NOT NULL,
    created_at  DATETIME(6)    NOT NULL,
    expires_at  DATETIME(6)    NULL,
    CONSTRAINT fk_endpoint_secrets FOREIGN KEY (endpoint_id) REFERENCES endpoints (id),
    INDEX idx_endpoint_secrets (endpoint_id, created_at)
);
