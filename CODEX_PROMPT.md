# Codex 구현 요청서

## 역할

너는 Java 21, Spring Boot, JPA, PostgreSQL, Redis와 장애 대응을 구현하고 검증하는 시니어 백엔드 엔지니어다.

이 저장소에서 DB 에러 이력과 Redis 최근 60초 rolling window를 결합해 에러 급증을 감지하고, Redis 장애 시 DB 집계로 fallback하며, cooldown과 비동기 retry 알림의 효과를 측정하는 재현 가능한 로컬 프로젝트를 구현한다.

## 필수 사전 확인

작업 전에 루트의 다음 문서를 모두 끝까지 읽는다.

1. `AGENTS.md`
2. `PROJECT_SPEC.md`
3. `PROGRESS.md`가 있으면 해당 문서

규칙이 충돌하면 `AGENTS.md`를 우선한다. 기존 구현과 테스트를 먼저 확인하고 완료된 작업을 다시 만들거나 되돌리지 않는다.

## 핵심 요구사항

### 에러 저장과 조회

- `POST /errors`로 모든 합성 에러를 PostgreSQL에 먼저 저장한다.
- 서버 `received_at`을 실시간 집계 기준으로 사용한다.
- source와 시간 범위를 이용한 에러 추이 API를 제공한다.
- `(source, received_at DESC)` 보조 인덱스를 구성한다.

### Redis rolling window

- 단일 키 fixed window를 sliding window라고 부르지 않는다.
- source별 1초 bucket에 Lua 기반 `INCR + EXPIRE`를 적용한다.
- 현재 초를 포함한 최근 60개 bucket을 합산한다.
- Redis 명령 timeout 기본값은 100ms로 설정 가능하게 한다.

### DB fallback

- Redis 연결 실패 또는 timeout이면 같은 source와 같은 60초 범위를 DB `COUNT`한다.
- fallback을 유발한 예외와 실행 경로를 메트릭 및 측정 결과에 남긴다.
- 프로그래밍 오류를 catch-all로 숨기지 않는다.

### threshold와 cooldown

- 기본 threshold 100건, window 60초, cooldown 60초를 설정값으로 둔다.
- count가 threshold 이상일 때만 cooldown을 시도한다.
- 정상 경로는 Redis `SET NX EX`로 한 요청만 선점한다.
- Redis 장애 경로는 DB unique key와 조건부 upsert로 한 요청만 선점한다.
- cooldown OFF는 benchmark profile에서만 허용한다.

### 비동기 알림과 retry

- 실제 유료 알림 서비스 대신 로컬 WireMock HTTP endpoint를 호출한다.
- 별도 Spring Bean에서 `@Async`와 `@Retryable`를 적용한다.
- bounded executor를 사용한다.
- connection/timeout/429/5xx만 재시도하고 일반 4xx는 재시도하지 않는다.
- 최대 시도 횟수와 exponential backoff를 설정으로 관리한다.
- 성공/최종 실패와 실제 시도 횟수를 `alert_delivery`에 기록한다.
- logical alert ID를 idempotency key로 전송한다.

### 측정

- 에러 N번째 수신부터 알림 endpoint 수신까지 지연
- Redis 정상 경로와 DB fallback 경로의 count 시간 및 API p95
- DB 집계 인덱스 ON/OFF EXPLAIN ANALYZE BUFFERS JSON
- 100 errors/s에서 API p50/p95/p99와 실패율
- cooldown OFF/ON의 queued/attempt/sent/failed/suppressed 횟수
- 주요 조건 3회 반복, raw CSV/JSON과 환경 정보 보존

## 환경 제약

- Java 21 / Spring Boot 3.x / Gradle
- PostgreSQL, Redis, WireMock Docker Compose
- Flyway, Testcontainers, JUnit 5, k6
- Windows PowerShell 재현 스크립트
- 모든 구현·테스트·측정·문서화는 로컬에서 무료로 실행
- AWS, 유료 API, 유료 SaaS, 무료 체험, 결제수단 등록 서비스 사용 금지
- 실제 회사 데이터와 개인정보 사용 금지

## 구현 시 주의사항

- DB transaction이 commit되기 전에 Redis counter를 증가시키지 않는다.
- `@Transactional`, `@Async`, `@Retryable` self-invocation을 만들지 않는다.
- JPA `save()` 호출만으로 commit 완료를 가정하지 않는다. transaction 경계를 테스트한다.
- Redis와 DB window의 Clock과 범위 경계를 동일하게 유지한다.
- source를 Redis key에 무제한 문자열로 직접 넣지 않는다.
- Redis command마다 새 connection을 만들지 않는다.
- fallback DB COUNT에 불필요한 Entity 조회를 사용하지 않는다.
- cooldown을 `GET 후 SET`으로 구현하지 않는다.
- async executor를 무제한 thread/queue로 만들지 않는다.
- retry 내부에서 새로운 logical alert를 생성하지 않는다.
- 알림 성공 전에 SENT로 기록하지 않는다.
- metric tag에 traceId, message, alertId를 넣지 않는다.
- 인덱스 OFF 실험에서 PK 인덱스를 제거하지 않는다.
- Redis나 OS cache를 초기화했다고 근거 없이 주장하지 않는다.
- 측정값이나 성능 향상률을 추정해 작성하지 않는다.

## 작업 단계

전체 작업을 한 번에 하지 말고 `AGENTS.md`의 1~6단계를 따른다. 한 단계가 끝날 때마다 테스트하고 `PROGRESS.md`를 갱신한 뒤 멈춘다.

### 이번 최초 작업: 1단계만 수행

이번 작업에서는 다음만 구현한다.

- Java 21 / Spring Boot / Gradle 프로젝트 초기 구성
- PostgreSQL, Redis, WireMock Docker Compose
- Flyway 기본 스키마
- 소규모 smoke seed
- 애플리케이션 설정과 profile 기반 로컬 구성
- DB/Redis/가짜 알림 endpoint 연결 확인
- Actuator health 확인
- 관련 단위·통합 smoke 테스트
- README의 1단계 실행 방법
- `docs/decisions.md`
- `PROGRESS.md`

이번 단계에서 스키마는 이후 구현에 필요한 `error_event`, `alert_cooldown`, `alert_delivery`를 만들 수 있지만, 다음 기능을 선행 구현하지 않는다.

- 완성된 에러 저장·추이 API
- Redis 60초 rolling counter
- DB fallback 전환 로직
- cooldown 선점 구현
- 비동기 retry 알림 구현
- 100 RPS 벤치마크
- 인덱스 ON/OFF 전체 EXPLAIN 수집
- 결과 수치 또는 블로그 초안
- 2단계 이후 작업

## 검증과 보고

계획만 작성하고 멈추지 말고 1단계 범위의 구현과 검증까지 수행한다.

운영체제 구성요소나 별도 프로그램 설치, 관리자 권한이 필요하면 임의로 실행하지 말고 이유와 사용자가 실행할 명령을 먼저 설명한다.

완료 후 `PROGRESS.md`에 다음을 기록한다.

1. 완료 내용
2. 실행한 테스트와 실제 결과
3. 현재 정상 동작하는 기능
4. 미완료 작업
5. 발생한 오류와 확인된 원인
6. 다음 단계
7. 재현 명령어
8. 변경한 주요 파일

마지막 응답에는 완료 내용, 테스트 결과, 현재 상태와 2단계에서 진행할 내용을 요약하고 멈춘다. 사용자가 계속 진행하라고 하기 전까지 2단계를 시작하지 않는다.

