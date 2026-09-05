CREATE TABLE error_event (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    message VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    trace_id VARCHAR(100),
    run_id VARCHAR(100) NOT NULL,
    CONSTRAINT chk_error_event_severity
        CHECK (severity IN ('ERROR', 'WARN', 'CRITICAL'))
);

CREATE TABLE alert_cooldown (
    scope_key VARCHAR(160) PRIMARY KEY,
    next_allowed_at TIMESTAMPTZ NOT NULL,
    token UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE alert_delivery (
    id UUID PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    run_id VARCHAR(100) NOT NULL,
    rolling_count BIGINT NOT NULL,
    count_path VARCHAR(16) NOT NULL,
    cooldown_path VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    threshold_event_received_at TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL,
    first_attempt_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    CONSTRAINT chk_alert_delivery_count_path
        CHECK (count_path IN ('REDIS', 'DB_FALLBACK')),
    CONSTRAINT chk_alert_delivery_cooldown_path
        CHECK (cooldown_path IN ('REDIS', 'DB', 'DISABLED')),
    CONSTRAINT chk_alert_delivery_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_alert_delivery_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_alert_delivery_rolling_count
        CHECK (rolling_count >= 0)
);
