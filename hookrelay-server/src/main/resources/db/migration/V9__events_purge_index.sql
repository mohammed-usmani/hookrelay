-- Retention purges payloads with "created_at < cutoff AND payload_purged_at IS NULL". This index
-- lets each batch start at the unpurged rows instead of re-walking every purged one.
CREATE INDEX idx_events_purge ON events (payload_purged_at, created_at);
