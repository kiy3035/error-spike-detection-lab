# Error Spike Detection Lab

DB 에러 이력과 Redis 기반 급증 감지를 로컬에서 재현하기 위한 개인 기술 블로그 실험 저장소입니다. 모든 데이터와 알림은 합성 데이터이며, 외부 유료 서비스나 실제 Slack·SMS·이메일을 사용하지 않습니다.

## 현재 구현 범위

현재 1단계 인프라 기반부터 4단계 cooldown과 비동기 재시도 알림까지 제공합니다.

- Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.12.1
- PostgreSQL 16.8, Redis 7.4.2, WireMock 3.13.1 Docker Compose
- Flyway 공통 스키마와 `local` profile 전용 smoke seed
- PostgreSQL·Redis·WireMock 연결과 Actuator health 확인
- Docker 없는 단위 테스트와 Testcontainers 통합 smoke 테스트
- `POST /errors` 합성 에러 저장
- `GET /errors` 서버 수신 시각 기준 페이지 조회
- `GET /errors/trend` 분/시간 단위 추이 조회
- Redis rolling window와 DB fallback
- `count >= threshold` 판정과 Redis/DB 원자 cooldown
- bounded executor 기반 비동기 WireMock 알림과 선택적 재시도
- `GET /alerts` 알림 상태·시도 이력 조회

k6 100 RPS와 인덱스 ON/OFF 측정은 5단계에서 수행합니다.

## 에러 API

합성 에러 저장:

```powershell
$body = @{
  source = "order-api"
  errorCode = "SYNTHETIC_TIMEOUT"
  severity = "ERROR"
  message = "synthetic timeout"
  occurredAt = "2026-09-06T00:00:00Z"
  traceId = "manual-0001"
  runId = "manual-run"
} | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/errors -Method Post -ContentType "application/json" -Body $body
```

서버 `received_at` 기준 이력과 추이 조회:

```powershell
Invoke-RestMethod "http://localhost:8080/errors?source=order-api&from=2026-09-05T00:00:00Z&to=2026-09-07T00:00:00Z&page=0&size=20"
Invoke-RestMethod "http://localhost:8080/errors/trend?source=order-api&from=2026-09-05T00:00:00Z&to=2026-09-05T12:00:00Z&bucket=HOUR"
```

시간 범위는 `[from, to)`이고 최대 7일, 페이지 크기는 최대 100, 추이 결과는 최대 1000 bucket입니다. 추이의 빈 구간은 0으로 반환합니다.

저장 응답에는 `countPath`, `rollingCount`, `thresholdExceeded`, `cooldownAcquired`, `alertQueued`가 포함됩니다. `alertQueued=true`는 DB에 `PENDING` 이력을 만들고 비동기 작업을 등록했다는 뜻이며 전송 성공을 뜻하지 않습니다. 정상 경로는 source SHA-256 scope의 1초 bucket 60개를 합산하며 bucket TTL은 120초입니다. Redis 연결 실패 또는 기본 100ms 명령 timeout에서만 DB `(windowStart, windowEnd]` count로 전환합니다.

기본 threshold와 cooldown은 각각 100건과 60초입니다. Redis count 경로는 `SET NX EX`로, DB fallback 경로는 조건부 PostgreSQL upsert 한 문장으로 source별 cooldown을 선점합니다. 비동기 전송은 core 2, max 4, queue 200의 bounded executor를 사용합니다. 연결 오류, timeout, HTTP 429와 5xx만 최대 3회(100ms, 200ms backoff) 재시도하며 일반 4xx는 한 번만 시도합니다. 각 요청에는 alert UUID를 `Idempotency-Key`로 전송합니다.

로컬 검증용 알림 이력 조회:

```powershell
Invoke-RestMethod "http://localhost:8080/alerts?runId=manual&page=0&size=20"
Invoke-RestMethod "http://localhost:8080/alerts?source=order-api&status=SENT&from=2026-09-06T00:00:00Z&to=2026-09-07T00:00:00Z"
```

`runId`, `source`, `status`, `from/to`는 선택 조건이며, `from/to`는 함께 지정하고 최대 7일 범위만 허용합니다. 페이지 크기는 최대 100입니다.

cooldown 비활성화는 로컬 측정 전용 `benchmark` profile에서만 제공합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "local,benchmark"
```

## 실행 환경

운영체제 구성요소나 별도 프로그램을 자동 설치하지 않습니다. 다음 명령은 이미 설치된 Java, Docker와 k6를 확인합니다.

```powershell
java -version
docker version
docker compose version
k6 version
```

검증 당시 주요 버전은 `results/environment.json`에 기록했습니다.

## 로컬 인프라 시작

```powershell
Copy-Item .env.example .env
docker compose up -d --wait
docker compose ps
```

`.env`의 값은 로컬 전용 예시값입니다. 애플리케이션은 `local` profile에서 다음 기본 포트를 사용합니다.

| 구성요소 | 주소 |
| --- | --- |
| PostgreSQL | `localhost:5432/error_detection` |
| Redis | `localhost:6379` |
| WireMock health | `http://localhost:8089/mock/health` |
| WireMock alert stub | `http://localhost:8089/mock/alerts` |
| 애플리케이션 | `http://localhost:8080` |

## 애플리케이션 시작과 health 확인

별도 PowerShell 창에서 실행합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\gradlew.bat --no-daemon bootRun
```

다른 창에서 확인합니다.

```powershell
.\scripts\verify-stage1.ps1
```

또는 직접 확인합니다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 8
Invoke-RestMethod http://localhost:8089/mock/health
Invoke-WebRequest http://localhost:8089/mock/alerts -Method Post -ContentType "application/json" -Body '{"alertId":"stage1-smoke"}'
```

`/actuator/health`는 PostgreSQL, Redis, 로컬 WireMock health contributor를 함께 검사합니다. 알림 stub은 애플리케이션의 비동기 알림 요청에 202를 반환합니다.

## 테스트

Docker가 필요 없는 단위 테스트:

```powershell
.\gradlew.bat --no-daemon test
```

PostgreSQL·Redis Testcontainers와 in-process WireMock을 사용하는 통합 smoke 테스트:

```powershell
.\gradlew.bat --no-daemon integrationTest
```

`check`는 두 테스트 묶음을 모두 실행합니다. 통합 테스트는 Docker daemon이 실행 중이어야 하며, 테스트 컨테이너는 종료 시 Testcontainers가 정리합니다.

## 종료

```powershell
docker compose down
```

seed와 Flyway 이력을 포함한 로컬 DB 볼륨까지 새로 시작할 때만 다음 명령을 사용합니다.

```powershell
docker compose down -v
```

`-v`는 로컬 PostgreSQL·Redis 볼륨과 Flyway 이력을 삭제하므로 seed를 완전히 다시 만들 때만 사용합니다.

## 주요 파일

- `compose.yaml`: PostgreSQL, Redis, WireMock 서비스
- `src/main/resources/db/migration/V1__create_base_schema.sql`: 이후 단계를 위한 기본 테이블
- `src/main/resources/db/local/V2__insert_smoke_seed.sql`: local profile 전용 결정적 seed
- `src/main/resources/application-local.yml`: 로컬 연결 설정
- `src/main/resources/application-benchmark.yml`: 로컬 측정 전용 cooldown OFF 설정
- `src/main/java/dev/errordetection/alert/*`: threshold, cooldown, delivery, async/retry 구현
- `src/integrationTest/.../InfrastructureSmokeIntegrationTest.java`: 인프라 통합 smoke 테스트
- `docs/decisions.md`: 1단계 설계 결정과 범위 경계
- `PROGRESS.md`: 실제 실행 결과와 다음 작업
