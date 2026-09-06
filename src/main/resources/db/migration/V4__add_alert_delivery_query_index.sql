CREATE INDEX idx_alert_delivery_run_id_queued_at
    ON alert_delivery (run_id, queued_at DESC);
