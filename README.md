# Error Spike Detection Lab

DB 에러 이력과 Redis 기반 급증 감지를 로컬에서 재현하기 위한 개인 기술 블로그 실험 저장소입니다. 모든 데이터와 알림은 합성 데이터이며, 외부 유료 서비스나 실제 Slack·SMS·이메일을 사용하지 않습니다.

## 1단계 현재 범위

이번 단계는 프로젝트와 로컬 인프라 기반만 제공합니다.

- Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.12.1
- PostgreSQL 16.8, Redis 7.4.2, WireMock 3.13.1 Docker Compose
- Flyway 공통 스키마와 `local` profile 전용 smoke seed
- PostgreSQL·Redis·WireMock 연결과 Actuator health 확인
- Docker 없는 단위 테스트와 Testcontainers 통합 smoke 테스트

에러 저장 API, 추이 조회, rolling window, DB fallback, cooldown, 비동기 retry, k6 부하 측정은 다음 단계에서 구현합니다.

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

`/actuator/health`는 PostgreSQL, Redis, 로컬 WireMock health contributor를 함께 검사합니다. 알림 stub은 연결 확인만을 위한 202 응답이며, 아직 애플리케이션 알림 전송 기능은 구현하지 않았습니다.

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
- `src/integrationTest/.../InfrastructureSmokeIntegrationTest.java`: 인프라 통합 smoke 테스트
- `docs/decisions.md`: 1단계 설계 결정과 범위 경계
- `PROGRESS.md`: 실제 실행 결과와 다음 작업
