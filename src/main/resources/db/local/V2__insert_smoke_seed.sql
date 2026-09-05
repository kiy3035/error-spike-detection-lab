INSERT INTO error_event (
    id,
    source,
    error_code,
    severity,
    message,
    occurred_at,
    received_at,
    trace_id,
    run_id
)
VALUES (
    1,
    'smoke-api',
    'SYNTHETIC_SMOKE',
    'ERROR',
    'synthetic smoke seed',
    TIMESTAMPTZ '2026-09-05 00:00:00+00',
    TIMESTAMPTZ '2026-09-05 00:00:01+00',
    'smoke-trace-0001',
    'stage1-smoke'
)
ON CONFLICT (id) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('error_event', 'id'),
    GREATEST((SELECT MAX(id) FROM error_event), 1),
    true
);
