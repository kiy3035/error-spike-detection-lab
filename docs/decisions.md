# 설계 결정 기록

## 2026-09-05: 1단계 인프라 기반

### 고정 버전

Spring Boot `3.5.16`, Gradle Wrapper `8.12.1`, PostgreSQL `16.8-alpine`, Redis `7.4.2-alpine`, WireMock `3.13.1`을 고정했다. `latest`와 동적 의존성은 재현성을 떨어뜨리므로 사용하지 않았다.

### Compose 서비스

PostgreSQL, Redis, WireMock을 하나의 `compose.yaml`에 둔다. 애플리케이션은 로컬에서 `local` profile로 실행해 서비스 포트에 연결한다. 데이터베이스와 Redis에는 명명된 볼륨을 사용해 애플리케이션 재시작과 인프라 수명주기를 분리했다.

### profile 기반 설정

공통 설정은 `application.yml`, 로컬 호스트·자격증명·Flyway local seed 경로는 `application-local.yml`에 둔다. 비밀번호는 코드에 저장하지 않고 `.env.example`에 로컬 전용 예시값만 제공한다.

### Flyway 범위

`V1__create_base_schema.sql`은 이후 기능에 필요한 `error_event`, `alert_cooldown`, `alert_delivery`의 구조만 만든다. 에러 저장·조회 API나 cooldown·알림 로직은 1단계에서 구현하지 않는다. smoke seed는 local profile에서만 `V2__insert_smoke_seed.sql`로 적용해 기본 프로필의 데이터 오염을 피한다.

### WireMock health 확인

실제 알림 서비스 대신 로컬 WireMock을 사용한다. `/mock/health`와 `/mock/alerts` stub을 제공하고, `NotificationEndpointHealthIndicator`가 `/mock/health`를 호출해 Actuator 전체 health에 포함한다. 알림 전송, async, retry는 다음 단계 범위다.

### 테스트 분리

빠른 단위 테스트는 `test` task에 두고, Docker가 필요한 PostgreSQL·Redis 검증은 별도 `integrationTest` task로 분리했다. 통합 smoke는 Testcontainers로 고정 이미지 컨테이너를 실행하고 WireMock은 테스트 JVM에서 동적 포트로 띄운다.

### health timeout

WireMock 최초 응답의 로컬 기동 지연으로 1초 timeout이 실제 Compose health에서 간헐적으로 전체 상태를 `DOWN`으로 만들었다. stage 1 연결 확인의 안정성을 위해 WireMock health 호출 timeout은 connect 2초/read 3초로 두었다. Redis 100ms 명령 timeout은 rolling counter 단계의 별도 설정으로 남긴다.

## 2026-09-06: 2단계 에러 저장과 조회

### 시간과 조회 경계

실시간 집계 기준은 요청의 `occurredAt`이 아니라 주입된 UTC `Clock`으로 생성한 `receivedAt`이다. 이력·추이 API 범위는 `[from, to)`로 고정하고 최대 7일로 제한했다. 다음 단계의 DB fallback은 명세대로 `(windowStart, windowEnd]`를 별도 count 메서드로 사용한다.

### 추이 집계와 빈 구간

외부 입력을 SQL 문자열에 직접 넣지 않기 위해 bucket은 `MINUTE`, `HOUR` enum만 허용한다. PostgreSQL `date_trunc` 결과를 받은 뒤 애플리케이션에서 빈 구간을 0으로 채운다. 응답 폭증을 막기 위해 결과 bucket을 최대 1000개로 제한한다.

### 페이지와 식별자 제한

이력 페이지 크기는 최대 100건이다. source, errorCode, traceId, runId는 길이와 허용 문자를 검증하며, 요청 본문 값과 내부 예외는 로그나 오류 응답에 그대로 노출하지 않는다. runId가 없으면 로컬 수동 요청을 뜻하는 `manual`을 저장한다.

### 인덱스

실제 이력 조회와 이후 fallback 조건이 사용하는 `(source, received_at DESC)` 보조 인덱스를 V3 migration으로 추가했다. 5단계 Index OFF/ON 실험에서는 이 인덱스만 대상으로 하고 PK 인덱스는 변경하지 않는다.

## 2026-09-06: 3단계 rolling window와 DB fallback

### 1초 bucket과 TTL

Redis 정상 경로는 현재 초와 직전 59초의 source별 bucket을 합산한다. 현재 bucket의 `INCR`와 최초 생성 시 `EXPIRE`는 Lua 한 번으로 원자 처리한다. TTL은 window 60초보다 여유 있는 120초로 고정해 경계 bucket을 안전하게 읽는다. 이는 1초 해상도의 rolling window이며 이벤트별 정확한 sliding log로 표현하지 않는다.

### Redis key scope

검증된 source도 Redis 키에 평문으로 직접 넣지 않고 SHA-256 전체 64자리로 변환한다. `error:count:{hash}:epochSecond` 형식으로 source를 격리하고 같은 source bucket이 Redis Cluster hash tag를 공유할 수 있게 했다.

### fallback 경계와 예외 분류

DB fallback은 저장에 사용한 같은 `receivedAt`을 종료점으로 `(receivedAt - 60초, receivedAt]`를 count한다. `RedisConnectionFailureException`, `QueryTimeoutException`, 원인 체인에 Lettuce 명령 timeout이 있는 `RedisSystemException`만 fallback한다. 그 밖의 변환·overflow·프로그래밍 오류는 그대로 전파한다.

### 측정 지표

`error.events.received`, `error.counter.requests{path}`, `error.counter.duration{path}`, `error.counter.fallbacks{reason}`을 기록한다. source, traceId, message 같은 고카디널리티 값은 tag에 넣지 않는다. 처리시간은 `System.nanoTime()` 기반으로 측정한다.

## 2026-09-06: 4단계 cooldown과 비동기 재시도 알림

### threshold와 cooldown 경로

기본 판정은 최근 60초 count가 100 이상일 때다. Redis count 경로는 source SHA-256 scope의 키에 `SET NX EX 60`을 실행하고, DB fallback count 경로는 `alert_cooldown`의 unique `scope_key`에 조건부 `INSERT ... ON CONFLICT DO UPDATE ... WHERE ... RETURNING` 한 문장만 사용한다. Redis cooldown 자체가 연결 실패 또는 명령 timeout이면 같은 요청에서 DB gate로 전환한다. Redis와 DB 사이에는 분산 트랜잭션이 없으므로 장애 전환 경계에서 두 저장소의 기존 선점 상태가 완전히 동기화된다고 주장하지 않는다.

cooldown OFF는 공통 기본값이 아니라 `local,benchmark` profile 조합에서만 활성화된다. 5단계 측정에서 각 run 전에 Redis key와 DB cooldown을 초기화해 profile 차이 이외의 상태를 격리한다.

### 트랜잭션과 비동기 경계

에러 저장 commit, rolling count, threshold 판정 순서를 유지한다. cooldown winner만 `AlertDeliveryService`의 짧은 `REQUIRES_NEW` 트랜잭션으로 `PENDING`을 저장한 뒤 별도 `AsyncAlertSender` Bean을 호출한다. executor는 core 2, max 4, queue 200으로 제한하고 포화 시 `CallerRunsPolicy`를 사용한다. 이 정책은 작업 유실 대신 호출자 지연을 허용하는 선택이며 5단계 결과 해석 시 queue 포화 여부를 확인한다.

### 재시도와 idempotency

연결 오류, read timeout, HTTP 429와 5xx만 최대 3회 재시도한다. backoff는 100ms에서 시작해 2배로 증가한다. 일반 4xx와 그 밖의 HTTP 클라이언트 오류는 첫 시도에서 `FAILED`로 기록한다. 일시적 오류가 소진되면 `@Recover`가 `FAILED`를 기록한다. 모든 HTTP 시도 전에 attempt를 별도 commit하고 성공 응답 뒤 `SENT`와 `sent_at`을 기록한다.

logical alert UUID를 payload와 `Idempotency-Key` 헤더에 함께 넣는다. 다만 WireMock은 실제 멱등 저장소가 아니며, 네트워크 timeout 전에 상대가 요청을 처리했을 가능성까지 로컬 발신자만으로 제거할 수 없다는 한계가 있다.

### 조회와 metric

`GET /alerts`는 runId, source, status, queuedAt 범위와 최대 100건 페이지를 제공한다. 선택 조건만 동적 predicate에 포함해 PostgreSQL에서 null parameter 타입 추론 문제가 생기지 않게 했다. `(run_id, queued_at DESC)` 조회 인덱스를 V4 migration으로 추가했다. `error.alerts{result=queued|suppressed|sent|failed}`와 monotonic timer 기반 `error.alert.delivery.duration`을 기록하며 고카디널리티 식별자는 tag로 사용하지 않는다.

## 2026-09-13: 5단계 성능 측정

### 측정 경계와 원본값

API 전체 시간은 k6 `http_req_duration`, rolling count 구간은 응답의 `counterDurationNanos`로 분리했다. 후자는 이미 `System.nanoTime()`으로 측정하던 `CounterResult.durationNanos`를 응답에 노출한 값이며 wall clock 추정치가 아니다. N번째 감지부터 알림까지는 `alert_delivery.sent_at - threshold_event_received_at`으로 계산했다.

### 100 RPS와 정확한 N번째 실험 분리

100 RPS는 `constant-arrival-rate`로 30초 실행하고, 정확한 N번째 검증은 1 VU `shared-iterations` 100회로 분리했다. 동시 부하에서는 여러 요청이 같은 시점의 합계를 관찰해 cooldown 승자의 rolling count가 100보다 커질 수 있으므로 이를 정확한 100번째 이벤트라고 부르지 않는다. 순차 실험은 매회 alert row의 `rolling_count=100`을 자동 검증한다.

### dropped iteration 해석

Redis 중단 DB fallback은 100 RPS 목표 중 run당 148~151회를 k6가 시작하지 못했다. 전송된 HTTP 요청은 모두 성공했지만 목표 처리량을 달성한 것은 아니므로, 실행을 폐기하지 않고 완료 요청 수와 `droppedIterations`를 raw 결과에 함께 보존한다. Redis 연결 실패 감지 시간이 counter p95에 포함되므로 DB SQL 시간으로 오해하지 않는다.

### 인덱스 실험

OFF에서는 `idx_error_event_source_received_at`만 제거하고 `error_event_pkey` 존재를 매번 catalog에서 확인했다. 각 전환 뒤 `ANALYZE error_event`를 실행했다. 동일 30초 DB fallback 부하 뒤 ON은 `Index Only Scan`, OFF는 `Seq Scan`을 선택했다. 이 데이터 크기에서는 OFF plan의 실제 시간이 더 짧았으므로 인덱스 개선율을 계산하거나 효과를 일반화하지 않는다.

### 상태 초기화와 실행 순서

run마다 `alert_delivery`, `alert_cooldown`, `error_event`를 truncate하고, Redis 사용 조건은 `FLUSHDB`, WireMock은 request journal 삭제를 수행했다. 3회 주요 조건은 정순·역순·정순으로 교차했다. 고정된 Compose CPU/메모리 제한과 JVM 옵션을 사용했다. 컨테이너 CPU 사용률은 신뢰할 수 있는 동일 간격 표본을 확보하지 못해 수집하지 않았고, 호스트 CPU·RAM과 자원 제한만 환경 파일에 기록했다.
