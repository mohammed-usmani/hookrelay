CREATE TABLE applications (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    status      ENUM('ACTIVE', 'DISABLED') NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    INDEX idx_applications_created (created_at, id)
);

CREATE TABLE api_keys (
    id             UUID         NOT NULL PRIMARY KEY,
    application_id UUID         NOT NULL,
    prefix         CHAR(8)      NOT NULL,
    secret_hash    BINARY(32)   NOT NULL,
    name           VARCHAR(200) NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    last_used_at   DATETIME(6)  NULL,
    revoked_at     DATETIME(6)  NULL,
    CONSTRAINT uq_api_keys_prefix UNIQUE (prefix),
    CONSTRAINT fk_api_keys_app FOREIGN KEY (application_id) REFERENCES applications (id),
    INDEX idx_api_keys_app (application_id, created_at)
);
