CREATE TABLE ingestion_key_lock (lock_key VARCHAR(140) PRIMARY KEY);
ALTER TABLE alarm_history ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX history_retention_idx ON alarm_history(accepted_at, id);
CREATE TABLE event_receipt (
    event_id VARCHAR(128) PRIMARY KEY,
    history_id BIGINT NOT NULL UNIQUE,
    fingerprint VARCHAR(64) NOT NULL
);
CREATE TABLE query_snapshot (
    token VARCHAR(36) PRIMARY KEY,
    query_key VARCHAR(200) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX snapshot_expiry_idx ON query_snapshot(expires_at);
