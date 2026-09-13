import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://app:8080';
const rps = Number(__ENV.RPS || 100);
const duration = __ENV.DURATION || '30s';
const source = __ENV.SOURCE || 'stage5-source';
const runId = __ENV.RUN_ID || 'stage5-run';
const expectedCountPath = __ENV.EXPECTED_COUNT_PATH || 'REDIS';
const scenarioName = __ENV.SCENARIO_NAME || 'measurement';
const warmup = __ENV.ALLOW_DROPPED_ITERATIONS === 'true';
const nthMode = __ENV.NTH_MODE === 'true';

const counterDuration = new Trend('counter_duration_ms', true);
const redisPath = new Counter('count_path_redis');
const dbFallbackPath = new Counter('count_path_db_fallback');
const thresholdExceeded = new Counter('threshold_exceeded');
const cooldownAcquired = new Counter('cooldown_acquired');
const alertQueued = new Counter('alert_queued');
const alertSuppressed = new Counter('alert_suppressed');

const thresholds = {
  checks: ['rate>0.999'],
  http_req_failed: ['rate<0.001'],
};

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: nthMode
    ? {
        [scenarioName]: {
          executor: 'shared-iterations',
          vus: 1,
          iterations: 100,
          maxDuration: '5m',
        },
      }
    : {
        [scenarioName]: {
          executor: 'constant-arrival-rate',
          rate: rps,
          timeUnit: '1s',
          duration,
          preAllocatedVUs: 200,
          maxVUs: 1000,
          gracefulStop: '10s',
        },
      },
  thresholds,
};

export default function () {
  const sequence = exec.scenario.iterationInTest;
  const body = JSON.stringify({
    source,
    errorCode: 'SYNTHETIC_STAGE5',
    severity: 'ERROR',
    message: 'synthetic stage5 performance event',
    occurredAt: new Date().toISOString(),
    traceId: `${runId}-${sequence}`,
    runId,
  });
  const response = http.post(`${baseUrl}/errors`, body, {
    headers: { 'Content-Type': 'application/json' },
  });

  let result = null;
  try {
    result = response.json();
  } catch (_) {
    result = null;
  }

  const path = result && result.countPath ? result.countPath : '';
  redisPath.add(path === 'REDIS');
  dbFallbackPath.add(path === 'DB_FALLBACK');
  if (result && Number.isFinite(result.counterDurationNanos)) {
    counterDuration.add(result.counterDurationNanos / 1e6);
  }
  thresholdExceeded.add(Boolean(result && result.thresholdExceeded));
  cooldownAcquired.add(Boolean(result && result.cooldownAcquired));
  alertQueued.add(Boolean(result && result.alertQueued));
  alertSuppressed.add(Boolean(result && result.thresholdExceeded && !result.cooldownAcquired));

  check(response, {
    'status is 201': (value) => value.status === 201,
    'count path is expected': () => warmup
      ? path === 'REDIS' || path === 'DB_FALLBACK'
      : path === expectedCountPath,
    'counter duration is present': () => result && Number.isFinite(result.counterDurationNanos),
  });
}

export function handleSummary(data) {
  return {
    [__ENV.SUMMARY_PATH || '/results/k6/summary.json']: JSON.stringify(data, null, 2),
    stdout: `${scenarioName} summary saved.\n`,
  };
}
