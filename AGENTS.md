# 저장소 작업 규칙

## 1. 프로젝트 목적

이 저장소는 에러 이벤트를 DB에 영구 보관하면서 Redis 기반 최근 60초 카운터로 급증을 빠르게 감지하고, Redis 장애 시 DB 집계로 전환하며, cooldown 적용 전후의 중복 알림 차이를 로컬에서 측정하는 개인 기술 블로그 프로젝트다.

- 회사 프로젝트나 실제 운영 성과처럼 표현하지 않는다.
- 에러와 알림 데이터는 모두 합성 데이터로 구성한다.
- 블로그의 수치와 결론은 실제 실행 결과에서만 가져온다.
- 목표는 Redis가 항상 더 빠르다고 단정하는 것이 아니라, 정상 경로·DB fallback·인덱스·cooldown이 지연과 부하에 어떤 영향을 주는지 재현 가능한 실험으로 보여주는 것이다.

구현 전에 루트의 `PROJECT_SPEC.md`와 `CODEX_PROMPT.md`를 끝까지 읽는다. 지침이 충돌하면 `AGENTS.md`를 우선한다.

## 1.1 로컬·무료 실행 제약

모든 구현과 실험은 외부 결제 없이 로컬에서 재현할 수 있어야 한다.

- PostgreSQL, Redis와 가짜 알림 서버는 Docker Compose로 실행한다.
- Java, Spring Boot, JPA, Redis, k6, WireMock 또는 동등한 무료 오픈소스 도구만 사용한다.
- AWS, 유료 클라우드, 유료 APM, 종량제 API, 실제 Slack·SMS·이메일 발송 서비스를 사용하지 않는다.
- 무료 체험, 무료 크레딧 또는 결제수단 등록이 필요한 서비스도 사용하지 않는다.
- 알림 대상은 로컬 WireMock HTTP endpoint로 고정한다.
- 운영체제 구성요소나 별도 프로그램 설치, 관리자 권한이 필요한 작업은 임의로 실행하지 말고 필요성과 사용자가 실행할 명령을 먼저 설명한다.
- 결과에는 CPU, RAM, OS, Java/JVM, Spring Boot, PostgreSQL, Redis, Docker, k6 버전을 기록한다.

## 2. 저장소를 다루는 방식

- 기존 코드가 있으면 빌드 도구, 패키지 구조, 코딩 스타일과 완료된 단계를 먼저 파악한다.
- 빈 저장소라면 Java 21, Spring Boot 3.x, Gradle, Spring Data JPA, PostgreSQL, Spring Data Redis, Spring Retry를 기본으로 구성한다.
- 사소한 선택은 합리적인 기본값으로 진행하고 근거를 `docs/decisions.md`에 기록한다.
- 사용자가 만든 변경이나 무관한 파일을 덮어쓰거나 되돌리지 않는다.
- Redis 중단, 인덱스 생성·삭제, 대량 seed, 부하 실험은 로컬 Docker 실험 환경에서만 수행한다.
- 비밀값을 코드, 설정, 로그, 결과 파일에 넣지 않는다. `.env.example`에는 로컬 예시값만 둔다.
- 실제 벤치마크 전에 자동 테스트로 저장 정합성, window 계산, fallback, cooldown과 retry를 검증한다.

## 2.1 장기 작업 분할과 체크포인트

전체 구현과 부하 실험을 한 번에 끝내려고 하지 않는다.

- 기본적으로 한 번의 작업에서는 하나의 주요 단계만 완료한다.
- 각 단계가 끝나면 관련 테스트를 실행하고 실제 결과를 확인한다.
- 중간에 멈추더라도 컴파일되지 않는 코드나 절반만 변경된 설정을 남기지 않는다.
- 오래 걸리면 실험 조건을 몰래 줄이지 않는다. 완료된 결과와 중단 지점을 `PROGRESS.md`에 기록한다.
- 완료하지 않은 작업을 완료했다고 표현하지 않는다.
- 측정하지 않은 수치, 예상 알림 횟수 또는 성능 향상률을 만들어내지 않는다.

루트의 `PROGRESS.md`에는 다음을 계속 기록한다.

1. 완료한 작업
2. 실제 실행한 테스트와 결과
3. 현재 정상 동작하는 기능
4. 미완료 작업과 측정 대기 항목
5. 발생한 오류와 확인된 원인
6. 다음 작업에서 바로 시작할 내용
7. 실행 및 재현 명령어
8. 변경한 주요 파일
9. 생성된 측정 결과 파일 경로

각 단계가 끝나면 결과를 보고하고 멈춘다. 사용자가 계속 진행하라고 요청한 뒤에만 다음 단계를 시작한다. 다음 단계 시작 시 `AGENTS.md`, `PROJECT_SPEC.md`, `PROGRESS.md`를 다시 읽고 완료된 작업을 재구현하지 않는다.

### 1단계: 프로젝트와 로컬 인프라 기반

- Spring Boot/Gradle 프로젝트 구성
- PostgreSQL, Redis, WireMock Docker Compose 구성
- Flyway 기본 스키마와 소규모 smoke seed
- 애플리케이션, DB, Redis, 가짜 알림 endpoint 실행 확인
- 기본 health check와 관련 테스트

### 2단계: 에러 저장과 이력·추이 조회

- `POST /errors` 저장 API
- `received_at` 기준 시간 범위 집계와 추이 API
- `(source, received_at)` 인덱스
- 입력 검증, 페이지 제한, 민감정보 로그 방지
- 저장·조회·집계 통합 테스트

### 3단계: Redis rolling window와 DB fallback

- 1초 버킷 기반 최근 60초 Redis 카운터
- Lua를 이용한 `INCR + EXPIRE` 원자 처리
- Redis 100ms timeout 및 장애 분류
- 동일 시간 기준의 DB `COUNT` fallback
- Redis/DB 경로 식별 메트릭과 장애 통합 테스트

### 4단계: cooldown과 비동기 재시도 알림

- 임계값 판정과 Redis `SET NX EX` cooldown 선점
- Redis 장애 시 DB cooldown 원자 선점
- 별도 Bean의 `@Async` + `@Retryable` 알림 전송
- bounded executor, retry 대상 구분, `@Recover`
- 알림 delivery/attempt 이력과 중복 억제 동시성 테스트

### 5단계: 전체 측정 실험

- N번째 에러 수신부터 알림 전송까지 지연
- Redis 정상 경로와 DB fallback 경로 비교
- DB 집계 인덱스 ON/OFF와 EXPLAIN 비교
- 100 errors/s에서 API p50/p95/p99
- cooldown OFF/ON 알림 요청·성공 횟수 비교
- 각 주요 조건 3회 반복, raw CSV/JSON 보존

### 6단계: 문서와 블로그

- 아키텍처와 정상/장애 데이터 흐름
- 실험 통제 변수와 재현 절차
- 결과 표와 그래프용 데이터
- 실제 측정값 해석
- fixed window와 rolling window 차이
- 한계와 개선 방향
- 기술 블로그 초안과 README 최종 검증

## 3. 핵심 데이터 흐름

1. `POST /errors`가 입력을 검증한다.
2. 별도 트랜잭션 서비스가 `error_event`를 DB에 저장하고 커밋한다.
3. 커밋이 끝난 뒤 감지 서비스가 Redis 최근 60초 count를 계산한다.
4. Redis 연결 실패 또는 100ms timeout이면 DB에서 같은 `source`와 같은 시간 범위를 `COUNT`한다.
5. count가 임계값 이상이면 cooldown을 원자적으로 선점한다.
6. 선점한 요청만 알림 이력을 `PENDING`으로 만들고 비동기 알림 Bean을 호출한다.
7. 알림 Bean은 로컬 HTTP endpoint로 전송하고 일시적 실패만 재시도한다.
8. 최종 성공은 `SENT`, 재시도 소진은 `FAILED`로 기록한다.

DB 커밋 전에 Redis count를 증가시키지 않는다. DB rollback인데 Redis count만 증가하는 불일치를 피하기 위해 저장 트랜잭션이 정상 반환된 뒤 감지를 수행한다.

## 4. Redis window 규칙

### 4.1 단일 키 오해 방지

`INCR error:count`와 `EXPIRE 60`을 단일 키에 한 번만 적용하는 방식은 엄밀한 sliding window가 아니라 fixed window다. 이 프로젝트는 이를 sliding window라고 잘못 설명하지 않는다.

### 4.2 구현 방식

- 1초 단위 버킷 키를 사용한다. 예: `error:count:{orders}:<epochSecond>`
- 현재 버킷은 Lua script 안에서 `INCR`한다.
- 값이 처음 생성될 때만 `EXPIRE`를 설정한다.
- TTL은 60초 경계 버킷을 안전하게 읽을 수 있도록 window보다 여유 있게 설정하고 실제값을 문서화한다.
- 현재 초를 포함한 최근 60개 버킷을 합산한다.
- 결과는 1초 해상도의 rolling window이며 정확한 이벤트 단위 sliding log와 동일하다고 주장하지 않는다.
- 애플리케이션의 주입 가능한 `Clock`을 기준으로 Redis와 DB cutoff를 동일하게 계산한다.
- source별 키가 섞이지 않도록 key scope를 명시한다.

Lua script와 애플리케이션 사이의 반환 타입, 음수/overflow, TTL 누락을 테스트한다. Redis 키는 결과 파일에 전체 덤프하지 않는다.

## 5. DB 저장과 fallback 규칙

- 모든 수신 에러는 Redis 성공 여부와 관계없이 먼저 DB에 보관한다.
- 실시간 집계 기준은 클라이언트가 보낸 `occurred_at`이 아니라 서버가 부여한 `received_at`이다.
- fallback 쿼리는 Redis와 동일한 `source`, 동일한 시작·종료 시각을 사용한다.
- 기본 실험 인덱스는 `CREATE INDEX idx_error_event_source_received_at ON error_event (source, received_at DESC)`다.
- Index OFF/ON 실험에서는 이 보조 인덱스만 제거·생성한다. PK 인덱스를 제거하지 않는다.
- 인덱스 변경 뒤 `ANALYZE error_event`를 실행하고 카탈로그에서 실제 상태를 확인한다.
- `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`은 실제 fallback과 같은 조건으로 별도 수집한다.
- 프로그래밍 오류까지 fallback으로 숨기지 않는다. 연결 실패, 명령 timeout 등 명시한 Redis 가용성 예외만 DB fallback 대상으로 삼는다.

## 6. 임계값과 cooldown 규칙

- 기본 window, threshold와 cooldown은 설정값으로 둔다. 예시 기본값은 window 60초, threshold 100건, cooldown 60초다.
- 임계값 비교는 기본적으로 `count >= threshold`다.
- cooldown scope는 최소한 `source`를 포함하고 문서와 key/table에서 동일하게 사용한다.
- Redis 정상 경로는 `SET cooldown-key token NX EX <seconds>` 성공 요청만 선점한다.
- Redis 장애 fallback 경로는 DB cooldown 테이블의 조건부 INSERT/UPDATE 한 건으로 선점한다.
- DB cooldown 선점은 read-then-write로 구현하지 않는다. 동시 요청 중 하나만 성공하도록 unique key와 원자적 SQL을 사용한다.
- cooldown OFF는 로컬 실험 프로필에서만 허용한다. 기본 프로필은 항상 ON이다.
- Redis와 DB가 동시에 전환되는 경계의 완전한 전역 원자성을 과장하지 않는다. 구현한 동기화·보완 방식과 남은 중복 가능성을 문서화한다.

## 7. 비동기 알림과 retry 규칙

- `@Async`와 `@Retryable` 대상 메서드는 호출자와 다른 Spring Bean에 둬서 proxy가 실제 적용되게 한다.
- async executor는 core/max/queue 크기와 거부 정책을 명시한 bounded executor를 사용한다.
- 실제 외부 알림 대신 로컬 WireMock HTTP endpoint를 호출한다.
- retry는 연결 오류, timeout, HTTP 429와 5xx 같은 일시적 실패에만 적용한다.
- 일반적인 4xx와 입력 오류는 재시도하지 않는다.
- 최대 시도 횟수와 exponential backoff 값은 설정으로 고정하고 테스트에서 검증한다.
- `@Recover` 또는 동등한 최종 실패 처리로 delivery를 `FAILED`로 기록한다.
- 각 logical alert에 idempotency key를 포함한다. 네트워크 timeout 뒤 상대 서버가 이미 처리했을 가능성을 한계로 문서화한다.
- 비동기이므로 HTTP 응답 완료와 알림 성공을 동일 시각으로 취급하지 않는다.

## 8. 시간과 지연 측정 규칙

- `Clock`을 주입해 단위 테스트 시간을 결정적으로 제어한다.
- DB timestamp와 애플리케이션 timestamp의 기준을 혼용하지 않는다.
- N건 감지 지연의 시작점은 임계값을 처음 만족시킨 N번째 요청의 서버 `received_at`이다.
- 종료점은 가짜 알림 서버가 요청을 수신한 시각 또는 delivery `sent_at`이며 어떤 값을 썼는지 결과에 명시한다.
- `detected_at`, `queued_at`, `first_attempt_at`, `sent_at`을 분리해 저장하거나 측정한다.
- wall-clock 지연과 프로세스 내부 구간 시간에는 monotonic clock을 우선 사용한다.
- async queue가 모두 drain되기 전에 알림 횟수를 집계하지 않는다.

## 9. 테스트 원칙

- 단위 테스트: 60초 경계, 버킷 만료, threshold 직전/도달/초과, Clock 제어.
- DB 통합 테스트: 저장, 범위 count, trend, 인덱스 쿼리 정합성.
- Redis 통합 테스트: 동시 INCR, TTL, source 격리, 60개 버킷 합산.
- fallback 테스트: Redis 중단, 연결 거부, 100ms timeout 뒤 DB count와 결과 path 검증.
- cooldown 테스트: 동시 요청 여러 개 중 한 개만 Redis 또는 DB 선점 성공.
- retry 테스트: 500→500→200, 400, timeout, 최종 실패와 실제 시도 횟수 검증.
- end-to-end 테스트: N번째 에러에서 한 개의 알림이 비동기로 성공하는지 검증.
- Docker가 필요한 테스트와 필요 없는 테스트를 분리하고 README에 명령을 기록한다.
- 100 RPS 전체 부하 실험을 일반 `test` 태스크에 포함하지 않는다.

## 10. 성능 측정 규칙

### 10.1 기본 시나리오

| 시나리오 | Redis | DB 집계 인덱스 | cooldown | 목적 |
| --- | --- | --- | --- | --- |
| REDIS_COOLDOWN_ON | 정상 | ON | ON | 정상 운영 경로 |
| REDIS_COOLDOWN_OFF | 정상 | ON | OFF | 중복 알림 기준선 |
| DB_FALLBACK_INDEX_ON | 장애/timeout | ON | ON | fallback 성능 |
| DB_FALLBACK_INDEX_OFF | 장애/timeout | OFF | ON | 인덱스 영향 |

- 기본 부하는 100 errors/s이며 측정 지속시간, warm-up과 사전 seed 크기를 고정해 기록한다.
- 각 주요 시나리오는 독립 warm-up 뒤 3회 측정한다.
- 실행 순서는 cache·열화 편향을 줄이도록 교차하거나 raw 결과에 순서를 기록한다.
- 각 run 전에 Redis counter/cooldown, WireMock request journal, 실험용 alert 이력을 격리 또는 초기화한다.
- raw 결과에는 실패한 run도 삭제하지 말고 실패 사유와 함께 남긴다.

### 10.2 수집 항목

- k6 `http_req_duration` p50/p95/p99, max, 실패율과 처리량
- 에러 N건 전송 시작부터 threshold 도달까지의 메타데이터
- N번째 에러 `received_at`부터 알림 endpoint 수신까지의 지연
- Redis count 처리시간과 DB fallback count 처리시간
- fallback 발생 횟수와 성공률
- cooldown OFF/ON의 alert queued/attempt/sent/failed/suppressed 횟수
- DB query EXPLAIN의 scan 유형, actual rows, buffers, planning/execution time
- 애플리케이션과 DB CPU는 안정적으로 수집 가능한 경우에만 보조 지표로 기록

평균만으로 결론 내리지 않고 p95를 중심으로 p50/p99와 원본 분포를 함께 보존한다. 서로 다른 시나리오의 데이터량과 환경이 같지 않으면 직접 비교하지 않는다.

## 11. 결과물 규칙

최종 결과물에는 최소한 다음을 포함한다.

- 실행 가능한 Spring Boot 애플리케이션
- PostgreSQL/Redis/WireMock Docker Compose
- Flyway migration과 결정적 seed
- 에러 저장·이력·추이 API
- Redis rolling-window detector와 DB fallback
- Redis/DB cooldown과 비동기 retry 알림
- 단위·통합·장애 테스트
- k6 시나리오와 PowerShell 실행 스크립트
- raw CSV/JSON, EXPLAIN JSON, 환경 정보와 요약 결과
- README, 아키텍처/결정 문서, 실제 결과 기반 블로그 초안

결과 폴더 예시는 다음과 같다.

```text
results/
  raw-runs.csv
  summary.csv
  environment.json
  k6/
  explain/
  alerts/
```

생성 산출물은 runId, 시나리오, 반복 번호를 포함해 덮어쓰지 않는다. 환경별 차이를 숨기지 않으며 로컬 측정값을 운영 성능처럼 표현하지 않는다.

