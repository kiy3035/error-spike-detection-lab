# Error Spike Detection Lab 프로젝트 명세

## 1. 한 줄 정의

DB 에러 이력과 Redis 60초 rolling window를 결합해 에러 급증을 감지하고, Redis 장애 시 DB fallback과 cooldown 적용 전후의 성능·중복 알림 차이를 로컬에서 측정하는 프로젝트다.

## 2. 문제 정의

DB `COUNT`만으로 최근 에러 수를 계산하면 모든 요청이 시간 범위 집계를 수행해 트래픽 증가 시 DB 부하와 응답 지연이 커질 수 있다. 반대로 Redis 카운터만 사용하면 개별 에러 이력과 장기 추이 조회가 어렵고 Redis 장애 시 감지가 중단된다.

따라서 다음 역할을 분리한다.

- PostgreSQL: 원본 에러 이력, 추이 조회, Redis 장애 시 시간 범위 집계, 알림 상태 보관
- Redis: 최근 60초 실시간 카운터와 정상 경로 cooldown
- DB cooldown: Redis 장애 중 중복 알림 억제를 위한 fallback 선점
- 비동기 알림: HTTP 요청 스레드와 분리된 재시도 가능한 로컬 webhook 전송

## 3. 사진에서 반영한 흐름

```text
에러 이벤트
  → POST /errors
  → DB 이력 저장
  → Redis 최근 60초 count
      → Redis 정상: 임계값 판정
      → Redis 장애/100ms timeout: DB 시간 범위 COUNT
  → 임계값 이상
  → cooldown 원자 선점
  → 비동기 알림 + retry
  → 로컬 알림 endpoint

DB 이력
  → 시간 범위 집계
  → GET /errors/trend
```

사진의 `INCR + EXPIRE = Sliding Window` 표기는 그대로 사용하지 않는다. 단일 키 INCR+EXPIRE는 fixed window이므로, 이 프로젝트는 1초 버킷 60개를 이동 합산하는 rolling window로 구현한다.

## 4. 범위

### 포함

- Java 21 / Spring Boot 3.x / Gradle
- Spring Web, Validation, Data JPA, Data Redis
- Spring Retry, Spring Async, Actuator, Micrometer
- PostgreSQL 16+ / Redis 7+ / Flyway
- Docker Compose
- WireMock 기반 로컬 알림 endpoint
- Testcontainers PostgreSQL/Redis와 WireMock 테스트
- k6 100 errors/s 부하 실험
- 인덱스 ON/OFF EXPLAIN
- CSV/JSON 결과와 블로그 초안

### 제외

- 실제 운영 로그 또는 개인정보
- 실제 Slack, 이메일, SMS, PagerDuty 전송
- Kafka와 외부 메시지 브로커
- Redis Cluster/Sentinel 운영 구성
- 멀티 리전, Kubernetes, AWS 배포
- 유료 APM, 유료 API, 무료 체험/결제수단 등록 서비스
- 운영 SLA 또는 운영 성과 주장

## 5. 권장 폴더명

```text
error-spike-detection-lab
```

권장 GitHub Description:

```text
DB 이력과 Redis 60초 rolling window를 결합해 에러 급증 감지, 장애 fallback, cooldown 효과를 측정하는 로컬 백엔드 실험.
```

## 6. 권장 기술 구성

- Java 21
- Spring Boot 3.x
- Gradle Wrapper
- Spring Data JPA / Hibernate
- Spring Data Redis / Lettuce
- Spring Retry / Spring Async
- PostgreSQL 16+
- Redis 7+
- Flyway
- Micrometer / Actuator
- JUnit 5
- Testcontainers
- WireMock
- k6
- Docker Compose
- Windows PowerShell 실행 스크립트

정확한 패치 버전은 구현 시점의 호환 가능한 안정 버전으로 고정하고 README와 환경 결과에 기록한다. 동적 버전과 `latest` 이미지는 사용하지 않는다.

## 7. API 설계

### 7.1 POST /errors

요청 예시:

```json
{
  "source": "order-api",
  "errorCode": "PAYMENT_TIMEOUT",
  "severity": "ERROR",
  "message": "synthetic timeout",
  "occurredAt": "2026-09-05T12:00:00Z",
  "traceId": "loadtest-000001"
}
```

응답은 저장된 ID와 감지 결과를 확인할 수 있게 하되 내부 예외나 민감한 정보를 노출하지 않는다.

```json
{
  "errorId": 101,
  "receivedAt": "2026-09-05T12:00:00.123Z",
  "countPath": "REDIS",
  "rollingCount": 100,
  "thresholdExceeded": true,
  "cooldownAcquired": true,
  "alertQueued": true
}
```

상태 코드는 영구 저장이 완료되었다는 의미로 `201 Created`를 권장한다. 알림은 비동기이므로 응답의 `alertQueued=true`가 전송 성공을 의미하지 않는다.

### 7.2 GET /errors/trend

예시:

```text
GET /errors/trend?source=order-api&from=...&to=...&bucket=MINUTE
```

- 허용 bucket은 사전에 정의한 enum으로 제한한다.
- 조회 범위 최대값과 결과 bucket 수 상한을 둔다.
- 빈 구간을 0으로 채울지 여부를 API 계약으로 고정한다.
- 쿼리 조건은 `source`, `received_at`을 사용한다.

### 7.3 GET /alerts

로컬 검증과 실험 결과 확인을 위한 조회 API다. 운영 관리 화면을 만드는 것은 범위에 포함하지 않는다.

- runId, source, status, from/to 조건
- page size 상한
- `PENDING`, `SENT`, `FAILED`와 attempt count 확인

## 8. 데이터 모델

### 8.1 error_event

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | 에러 식별자 |
| source | VARCHAR(64) | 서비스 또는 에러 발생원 |
| error_code | VARCHAR(64) | 합성 에러 코드 |
| severity | VARCHAR(16) | ERROR 등 제한된 값 |
| message | VARCHAR(500) | 합성 메시지, 길이 제한 |
| occurred_at | TIMESTAMPTZ | 요청이 전달한 발생 시각 |
| received_at | TIMESTAMPTZ | 서버 수신·집계 기준 시각 |
| trace_id | VARCHAR(100) | 합성 추적 ID |
| run_id | VARCHAR(100) | 실험 run 격리 키 |

실험 인덱스:

```sql
CREATE INDEX idx_error_event_source_received_at
    ON error_event (source, received_at DESC);
```

run별 완전 격리가 필요하면 실제 fallback 조건과 인덱스에 `run_id`를 포함할지 결정하고 `docs/decisions.md`에 기록한다. Reader가 쓰지 않는 인덱스를 성능 인덱스라고 주장하지 않는다.

### 8.2 alert_cooldown

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| scope_key | VARCHAR(160) PK | source 중심 cooldown 범위 |
| next_allowed_at | TIMESTAMPTZ | 다음 선점 가능 시각 |
| token | UUID | 선점 식별자 |
| updated_at | TIMESTAMPTZ | 갱신 시각 |

Redis 장애 fallback에서 unique key와 조건부 upsert를 사용한다. `SELECT` 후 `UPDATE`하는 경쟁 조건을 만들지 않는다.

### 8.3 alert_delivery

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | UUID PK | logical alert/idempotency key |
| source | VARCHAR(64) | 감지 범위 |
| run_id | VARCHAR(100) | 실험 run |
| rolling_count | BIGINT | 감지 시점 count |
| count_path | VARCHAR(16) | REDIS 또는 DB_FALLBACK |
| cooldown_path | VARCHAR(16) | REDIS, DB 또는 DISABLED |
| status | VARCHAR(16) | PENDING, SENT, FAILED |
| attempt_count | INTEGER | 실제 HTTP 시도 횟수 |
| threshold_event_received_at | TIMESTAMPTZ | N번째 이벤트 기준 시각 |
| detected_at | TIMESTAMPTZ | 임계값 판정 시각 |
| queued_at | TIMESTAMPTZ | 비동기 요청 등록 시각 |
| first_attempt_at | TIMESTAMPTZ | 최초 전송 시작 |
| sent_at | TIMESTAMPTZ | endpoint 성공 시각 |
| last_error | VARCHAR(500) | 정제된 최종 오류 |

비동기 작업과 원 요청의 transaction을 길게 공유하지 않는다. 상태 갱신은 짧은 별도 transaction으로 처리한다.

## 9. 저장 후 감지 순서

권장 컴포넌트 분리는 다음과 같다.

- `ErrorController`: 입력/응답
- `ErrorIngestionFacade`: 저장 후 감지 orchestration
- `ErrorPersistenceService`: 별도 public Bean의 `@Transactional` 저장
- `RollingErrorCounter`: Redis count와 DB fallback 선택
- `ThresholdDecisionService`: threshold와 cooldown 판단
- `CooldownGate`: Redis/DB 원자 선점
- `AlertDispatchCoordinator`: PENDING 생성과 async 호출
- `AsyncAlertSender`: 별도 Bean의 `@Async`, `@Retryable`
- `AlertDeliveryService`: attempt/status transaction 갱신

`ErrorPersistenceService.save()`가 정상 반환된 다음 count를 수행한다. self-invocation으로 transaction, async, retry proxy를 무효화하지 않는다.

## 10. Redis rolling window 상세

### 키

```text
error:count:{<source>}:<epochSecond>
error:cooldown:{<source>}
```

source는 길이와 허용문자를 검증하고, 실제 키는 충돌 없는 정규화 또는 hash를 사용할 수 있다.

### 카운터 알고리즘

1. 서버 `received_at`을 epochSecond로 변환한다.
2. 현재 초 bucket을 Lua에서 `INCR`한다.
3. 최초 증가라면 TTL을 설정한다.
4. 현재 bucket과 직전 59개 bucket을 합산한다.
5. count와 실행 경로를 반환한다.

Lua script로 적어도 현재 bucket의 INCR와 TTL 설정을 원자적으로 수행한다. TTL은 120초 같은 여유값으로 설정할 수 있으나 설정값과 이유를 문서화한다.

이 방식은 1초 해상도의 근사 rolling window다. 이벤트별 정확한 sliding log가 필요하면 Redis Sorted Set 방식이 대안이지만, 이번 비교의 주 구현에는 추가하지 않는다.

## 11. DB fallback 상세

논리 쿼리:

```sql
SELECT COUNT(*)
FROM error_event
WHERE source = :source
  AND received_at > :windowStart
  AND received_at <= :windowEnd;
```

- windowStart/windowEnd는 Redis 계산에 사용한 같은 `Clock`에서 만든다.
- 저장 commit 후 실행하므로 방금 수신한 이벤트가 count에 포함되어야 한다.
- Redis timeout 기본값은 100ms이며 설정으로 주입한다.
- 연결 실패와 timeout은 DB fallback으로 전환하고 counter path 메트릭을 증가시킨다.
- DB도 실패하면 성공 응답으로 숨기지 않는다. 에러 이력 저장 성공과 급증 감지 실패를 구분해 응답/로그/메트릭 정책을 문서화한다.

## 12. cooldown 상세

### Redis 정상

```text
SET error:cooldown:{source} <alertId> NX EX 60
```

성공한 요청만 알림을 생성한다. threshold를 넘은 모든 요청이 `GET` 후 `SET`하도록 구현하지 않는다.

### Redis 장애

DB `alert_cooldown`에서 다음 의미의 단일 조건부 문장으로 선점한다.

```text
행 없음 → INSERT 성공
행 있음 && next_allowed_at <= now → UPDATE 성공
아직 cooldown 중 → 변경 없음
```

PostgreSQL `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE ... RETURNING` 또는 동등한 원자 SQL을 사용한다. 반환 행 존재 여부로 선점 성공을 판단한다.

Redis 정상 cooldown과 DB fallback cooldown 사이에는 분산 transaction이 없다. 장애 전환 순간의 중복 가능성, 이를 줄이기 위해 선택한 동기화 전략과 한계를 결과 문서에 명시한다.

## 13. 알림과 retry 상세

가짜 endpoint 예시:

```text
POST http://wiremock:8080/mock/alerts
```

전송 payload에는 `alertId`, source, rollingCount, window, detectedAt을 넣고 `Idempotency-Key: <alertId>`를 보낸다.

권장 기본 정책:

- `@Async("alertExecutor")`
- `@Retryable` 최대 3회
- exponential backoff, 예: 100ms → 200ms
- retry 대상: connection error, timeout, 429, 5xx
- non-retry 대상: 그 외 4xx, 직렬화/검증 오류
- 모든 실패 뒤 `@Recover`에서 FAILED 기록

정확한 값은 설정에 두고 테스트로 시도 횟수와 간격을 검증한다. `Thread.sleep`으로 production retry를 직접 구현하지 않는다.

## 14. Micrometer 지표

최소 지표 예시:

- `error.events.received`
- `error.counter.requests{path=redis|db_fallback}`
- `error.counter.duration{path=redis|db_fallback}`
- `error.counter.fallbacks{reason=timeout|connection}`
- `error.alerts{result=queued|suppressed|sent|failed}`
- `error.alert.delivery.duration`
- async executor active/queued/rejected

tag에 error message, traceId, alertId처럼 cardinality가 큰 값을 넣지 않는다. source도 실험에서 제한된 allowlist만 tag로 허용한다.

## 15. 테스트 시나리오

### 자동 테스트

- error 1건 저장과 receivedAt 생성
- source/time range별 count와 trend
- 60초 경계 바깥 bucket 제외
- 동시 100건 INCR 결과 정합성
- Redis source 간 count 격리
- Redis 종료 시 DB count 전환
- 100ms 초과 fault에서 timeout fallback
- threshold 99에서는 알림 없음, 100에서 1건
- threshold 이후 동시 요청에서 cooldown winner 1건
- DB cooldown 동시성 winner 1건
- cooldown 만료 후 새 알림 가능
- WireMock 500, 500, 200에서 3회째 SENT
- WireMock 400에서 재시도 없이 FAILED
- 최종 5xx에서 `@Recover`와 FAILED

### 장애 주입

- Redis container stop: 연결 실패 fallback
- 무료 로컬 fault proxy 또는 Testcontainers Toxiproxy: 100ms timeout fallback
- WireMock scenario: 알림 retry

실험에 사용하지 않은 장애 방식을 사용했다고 문서에 쓰지 않는다.

## 16. 성능 실험

### 공통 조건

- 합성 source 하나를 기본으로 사용
- threshold 100, window 60초, cooldown 60초를 기본값으로 고정
- k6 constant-arrival-rate 100 iterations/s
- warm-up과 본 측정 시간을 명시
- 주요 조건 3회 반복
- 각 run의 application, Redis, DB 상태 초기화
- 동일 머신, JVM 옵션, Docker resource 조건 유지

### 실험 A: N건부터 알림까지

1. 상태 초기화
2. N건을 통제된 속도로 POST
3. N번째 event의 `received_at` 기록
4. WireMock 수신 또는 delivery `sent_at` 대기
5. `sent_at - threshold_event_received_at` 계산

Redis 정상과 DB fallback을 각각 수행하며 3회 원본값과 평균/최소/최대를 보존한다.

### 실험 B: Redis 대 DB fallback

동일한 seed, 부하, threshold, cooldown 조건에서 다음을 비교한다.

| 모드 | Redis 상태 | DB 인덱스 |
| --- | --- | --- |
| REDIS | 정상 | ON |
| DB_FALLBACK_INDEX_ON | 중단 또는 timeout | ON |
| DB_FALLBACK_INDEX_OFF | 중단 또는 timeout | OFF |

API p50/p95/p99, count 구간 시간, 성공률, fallback 횟수와 EXPLAIN을 수집한다.

### 실험 C: cooldown OFF 대 ON

- Redis 정상 상태
- 100 errors/s
- 동일 threshold/window/duration
- OFF는 로컬 benchmark profile에서만 실행
- queued, HTTP attempt, sent, failed, suppressed 횟수 비교
- async queue가 완전히 끝난 후 최종 횟수 확정

OFF 실험이 로컬 endpoint에 많은 요청을 만들 수 있으므로 최대 지속시간과 안전 상한을 명시한다. 측정 중 임의로 threshold를 바꾸지 않는다.

## 17. 결과 파일

```text
results/
  raw-runs.csv
  summary.csv
  environment.json
  k6/
    <scenario>-<repetition>.json
  explain/
    fallback-index-off.json
    fallback-index-on.json
  alerts/
    <scenario>-<repetition>.json
```

raw run 최소 컬럼:

- runId, scenario, repetition
- startedAt, endedAt, durationSeconds
- rate, sentEvents, failedEvents
- apiP50Ms, apiP95Ms, apiP99Ms, apiMaxMs
- counterPath, counterP95Ms, fallbackCount
- threshold, windowSeconds, cooldownSeconds, cooldownEnabled
- alertQueued, alertAttempts, alertSent, alertFailed, alertSuppressed
- thresholdToAlertMs
- indexMode, explainPath
- environmentPath

## 18. 완료 기준

다음 조건을 모두 충족해야 완료다.

1. 모든 에러가 DB에 저장되고 이력·추이 조회가 가능하다.
2. Redis 정상 시 1초 버킷 rolling count가 동작한다.
3. Redis 연결 실패와 100ms timeout에서 DB fallback이 검증된다.
4. 정상/장애 경로에서 cooldown 동시성 테스트가 통과한다.
5. `@Async`와 `@Retryable`가 실제 proxy 경로에서 작동한다.
6. N건 감지 지연, Redis/DB 비교, 100 RPS p95, cooldown 전후 횟수를 실제 측정한다.
7. DB 인덱스 ON/OFF 실행계획을 보존한다.
8. raw 결과에서 요약표를 재생성할 수 있다.
9. README 절차를 새 환경에서 재현할 수 있다.
10. 블로그 초안은 실제 수치만 사용하고 한계까지 설명한다.

