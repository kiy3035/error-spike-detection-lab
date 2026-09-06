CREATE INDEX idx_error_event_source_received_at
    ON error_event (source, received_at DESC);
