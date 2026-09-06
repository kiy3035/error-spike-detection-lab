# 진행 상황

## 1단계 상태: 완료

## 2단계 상태: 완료

## 3단계 상태: 완료

## 4단계 상태: 완료

## 완료한 작업

- [x] Java 21 / Spring Boot 3.5.16 / Gradle Wrapper 8.12.1 프로젝트 생성
- [x] PostgreSQL 16.8, Redis 7.4.2, WireMock 3.13.1 Compose 구성
- [x] 공통 Flyway V1 기본 스키마 구성
- [x] local profile 전용 결정적 smoke seed 구성
- [x] PostgreSQL·Redis·WireMock 연결용 설정과 Actuator health 구성
- [x] WireMock health custom health indicator 구성
- [x] 단위 테스트와 Docker 기반 통합 smoke 테스트 분리
- [x] README, `docs/decisions.md`, 환경 정보 작성
- [x] `POST /errors` 저장 API와 서버 `received_at` 생성
- [x] `GET /errors` 이력 페이지와 `GET /errors/trend` 추이 API
- [x] `(source, received_at DESC)` 보조 인덱스
- [x] 입력 검증, 최대 7일·100건·1000 bucket 제한, 정제된 오류 응답
- [x] Lua `INCR + EXPIRE` 기반 source별 1초 Redis bucket
- [x] 현재 초 포함 60개 bucket 합산과 TTL 120초
- [x] Redis 100ms timeout·연결 실패의 DB fallback
- [x] Redis/DB 경로·처리시간·fallback 사유 metric
- [x] `count >= threshold` 임계값 판정과 응답 결과 필드
- [x] Redis `SET NX EX` source cooldown과 DB 조건부 upsert fallback
- [x] cooldown OFF 로컬 benchmark profile
- [x] bounded executor의 별도 `@Async` + `@Retryable` 알림 Bean
- [x] 연결 오류·timeout·429·5xx 선택 재시도와 일반 4xx 무재시도
- [x] `PENDING`·attempt·`SENT`·`FAILED` 별도 트랜잭션 이력
- [x] UUID idempotency key payload/header와 `GET /alerts` 조회 API
- [x] Redis/DB cooldown 동시성·만료와 WireMock retry 통합 테스트

## 실제 실행한 테스트와 결과

| 명령 | 결과 |
| --- | --- |
| `gradlew.bat --no-daemon test` | 통과, 단위 테스트 2개 |
| `gradlew.bat --no-daemon integrationTest` | 통과, 통합 smoke 1개 |
| `docker compose config` | 통과 |
| `docker compose up -d --wait` | PostgreSQL·Redis·WireMock 모두 healthy |
| local `bootRun` + `scripts/verify-stage1.ps1` | 통과, Actuator `UP`, WireMock health 200, alert stub 202 |
| 2단계 `gradlew.bat --no-daemon test` | 통과, 단위 테스트 5개 |
| 2단계 `gradlew.bat --no-daemon integrationTest` | 통과, 통합 테스트 3개 |
| 3단계 `gradlew.bat --no-daemon test` | 통과, 단위 테스트 10개 |
| 3단계 `gradlew.bat --no-daemon integrationTest` | 통과, 통합 테스트 7개 |
| 4단계 `gradlew.bat --no-daemon test integrationTest` | 통과, 단위 테스트 13개·통합 테스트 14개 |

통합 테스트에서 Flyway seed 1건, PostgreSQL 조회, Redis SET/GET, WireMock health, Actuator `UP`을 확인했다. 최초 실제 Compose 확인에서는 WireMock 최초 응답이 1초를 넘어 `notificationEndpoint`가 DOWN이 되었고, connect 2초/read 3초로 조정했다. 조정 후 실제 애플리케이션을 재기동해 스크립트로 Actuator `UP`을 확인했다.

## 현재 정상 동작하는 기능

- `local` profile로 애플리케이션이 PostgreSQL에 연결되고 Flyway V1/V2를 적용한다.
- local DB에 `stage1-smoke` seed 1건이 생성된다.
- Spring Boot Actuator `/actuator/health`에 DB, Redis, 로컬 WireMock health가 포함된다.
- WireMock `/mock/health`는 200, `/mock/alerts`는 202를 반환한다.
- 단위·통합 smoke 테스트가 통과한다.
- 검증 종료 후 애플리케이션 `bootRun` 프로세스는 종료했으며, Compose 인프라는 재현 확인을 위해 현재 실행 중이다. 필요하면 `docker compose down`으로 종료한다.
- 합성 에러가 PostgreSQL에 저장되고 서버 수신 시각 기준으로 페이지·추이 조회된다.
- 조회 범위·페이지·bucket 상한과 입력 문자 제한이 적용된다.
- Redis 정상 시 1초 bucket rolling count를 반환하고, 연결 실패·100ms timeout 시 동일 `receivedAt` 기준 DB count로 전환한다.
- 기본 100건 threshold에서 Redis 또는 DB cooldown winner 한 건만 알림을 등록한다.
- 알림은 bounded executor에서 비동기로 로컬 WireMock에 전송되고 상태와 실제 attempt가 DB에 기록된다.
- 500→500→200은 3회째 `SENT`, 일반 400은 1회 뒤 `FAILED`, read timeout과 계속된 5xx는 3회 뒤 `FAILED`가 된다.
- `GET /alerts`에서 runId, source, status, queuedAt 조건으로 로컬 알림 이력을 조회할 수 있다.

## 미완료 작업과 측정 대기 항목

- 5단계 k6·인덱스·cooldown 성능 실험 및 결과 파일
- 6단계 문서 확장과 블로그 초안

## 발생한 오류와 확인된 원인

- 시스템 Gradle 명령이 없어 기존 사용자 Gradle 캐시의 Gradle 8.12.1로 Wrapper를 생성했다.
- 샌드박스에서 사용자 Docker 설정 파일과 WMI 조회가 권한 제한으로 차단됐다. 실제 Docker 검증은 승인된 로컬 Docker 명령으로 수행했다.
- WireMock 최초 health 호출이 1초 timeout을 초과해 전체 Actuator 상태가 일시적으로 DOWN이 되었다. local health timeout을 2초/3초로 늘렸다.
- Toxiproxy에 300ms downstream 지연을 주입해 100ms Redis timeout fallback을 재현했고, 별도 테스트에서 Redis 컨테이너 중단 연결 실패도 재현했다.
- 4단계 첫 알림 조회 구현에서 nullable 시각 parameter를 `:from IS NULL` 형태로 사용하자 PostgreSQL이 parameter 타입을 추론하지 못했다. null이 아닌 조건만 만드는 JPA Specification으로 변경한 뒤 통합 테스트가 통과했다.

## 다음 작업에서 바로 시작할 내용

4단계 cooldown과 비동기 retry 알림 검증이 완료되었다. 다음은 5단계 k6 100 RPS, Redis/DB fallback, 인덱스 ON/OFF, cooldown ON/OFF 실측과 raw 결과 보존이다.

## 실행 및 재현 명령어

```powershell
Copy-Item .env.example .env
docker compose up -d --wait
$env:SPRING_PROFILES_ACTIVE = "local"
.\gradlew.bat --no-daemon bootRun
# 별도 창
.\scripts\verify-stage1.ps1
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon integrationTest
Invoke-RestMethod "http://localhost:8080/alerts?runId=manual&page=0&size=20"
docker compose down
```

## 변경한 주요 파일

- `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- `compose.yaml`, `.env.example`, `.gitignore`, `.gitattributes`
- `src/main/java/dev/errordetection/*`
- `src/main/java/dev/errordetection/error/*`, `src/main/java/dev/errordetection/config/TimeConfiguration.java`
- `src/main/java/dev/errordetection/counter/*`, `src/integrationTest/java/dev/errordetection/RedisFallbackIntegrationTest.java`
- `src/main/java/dev/errordetection/alert/*`, `src/test/java/dev/errordetection/alert/*`
- `src/main/resources/db/migration/V4__add_alert_delivery_query_index.sql`
- `src/main/resources/application*.yml`, `src/main/resources/db/*`
- `src/test/java/*`, `src/integrationTest/java/*`
- `wiremock/mappings/*`, `scripts/verify-stage1.ps1`
- `README.md`, `docs/decisions.md`, `results/environment.json`

## 생성된 측정 결과 파일 경로

- 환경 정보: `results/environment.json`
- 성능 raw/summary와 EXPLAIN 결과: 아직 생성하지 않음(5단계 범위)
