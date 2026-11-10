-- Per-endpoint send pacing: the earliest moment the next send may start.
ALTER TABLE endpoints ADD COLUMN rate_next_send_at DATETIME(6) NULL;
