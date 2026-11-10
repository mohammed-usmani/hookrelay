CREATE TABLE event_types (
    id             UUID         NOT NULL PRIMARY KEY,
    application_id UUID         NOT NULL,
    name           VARCHAR(100) NOT NULL,
    description    VARCHAR(500) NULL,
    json_schema    JSON         NULL,
    `system`       BOOLEAN      NOT NULL DEFAULT FALSE,
    archived_at    DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    CONSTRAINT uq_event_types_name UNIQUE (application_id, name),
    CONSTRAINT fk_event_types_app FOREIGN KEY (application_id) REFERENCES applications (id),
    INDEX idx_event_types_list (application_id, created_at, id)
);
