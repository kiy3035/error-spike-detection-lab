# 진행 상황

## 1단계 상태: 완료

## 완료한 작업

- [x] Java 21 / Spring Boot 3.5.16 / Gradle Wrapper 8.12.1 프로젝트 생성
- [x] PostgreSQL 16.8, Redis 7.4.2, WireMock 3.13.1 Compose 구성
- [x] 공통 Flyway V1 기본 스키마 구성
- [x] local profile 전용 결정적 smoke seed 구성
- [x] PostgreSQL·Redis·WireMock 연결용 설정과 Actuator health 구성
- [x] WireMock health custom health indicator 구성
- [x] 단위 테스트와 Docker 기반 통합 smoke 테스트 분리
- [x] README, `docs/decisions.md`, 환경 정보 작성

## 실제 실행한 테스트와 결과

| 명령 | 결과 |
| --- | --- |
| `gradlew.bat --no-daemon test` | 통과, 단위 테스트 2개 |
| `gradlew.bat --no-daemon integrationTest` | 통과, 통합 smoke 1개 |
| `docker compose config` | 통과 |
| `docker compose up -d --wait` | PostgreSQL·Redis·WireMock 모두 healthy |
| local `bootRun` + `scripts/verify-stage1.ps1` | 통과, Actuator `UP`, WireMock health 200, alert stub 202 |

통합 테스트에서 Flyway seed 1건, PostgreSQL 조회, Redis SET/GET, WireMock health, Actuator `UP`을 확인했다. 최초 실제 Compose 확인에서는 WireMock 최초 응답이 1초를 넘어 `notificationEndpoint`가 DOWN이 되었고, connect 2초/read 3초로 조정했다. 조정 후 실제 애플리케이션을 재기동해 스크립트로 Actuator `UP`을 확인했다.

## 현재 정상 동작하는 기능

- `local` profile로 애플리케이션이 PostgreSQL에 연결되고 Flyway V1/V2를 적용한다.
- local DB에 `stage1-smoke` seed 1건이 생성된다.
- Spring Boot Actuator `/actuator/health`에 DB, Redis, 로컬 WireMock health가 포함된다.
- WireMock `/mock/health`는 200, `/mock/alerts`는 202를 반환한다.
- 단위·통합 smoke 테스트가 통과한다.
- 검증 종료 후 애플리케이션 `bootRun` 프로세스는 종료했으며, Compose 인프라는 재현 확인을 위해 현재 실행 중이다. 필요하면 `docker compose down`으로 종료한다.

## 미완료 작업과 측정 대기 항목

- 2단계 에러 저장·추이 조회 API
- 3단계 Redis rolling window와 DB fallback
- 4단계 cooldown과 비동기 retry 알림
- 5단계 k6·인덱스·cooldown 성능 실험 및 결과 파일
- 6단계 문서 확장과 블로그 초안

## 발생한 오류와 확인된 원인

- 시스템 Gradle 명령이 없어 기존 사용자 Gradle 캐시의 Gradle 8.12.1로 Wrapper를 생성했다.
- 샌드박스에서 사용자 Docker 설정 파일과 WMI 조회가 권한 제한으로 차단됐다. 실제 Docker 검증은 승인된 로컬 Docker 명령으로 수행했다.
- WireMock 최초 health 호출이 1초 timeout을 초과해 전체 Actuator 상태가 일시적으로 DOWN이 되었다. local health timeout을 2초/3초로 늘렸다.

## 다음 작업에서 바로 시작할 내용

1단계 산출물과 실제 Compose health 확인이 완료되었다. 사용자가 명시적으로 계속 진행할 때만 2단계 에러 저장 API를 시작한다.

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
docker compose down
```

## 변경한 주요 파일

- `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- `compose.yaml`, `.env.example`, `.gitignore`, `.gitattributes`
- `src/main/java/dev/errordetection/*`
- `src/main/resources/application*.yml`, `src/main/resources/db/*`
- `src/test/java/*`, `src/integrationTest/java/*`
- `wiremock/mappings/*`, `scripts/verify-stage1.ps1`
- `README.md`, `docs/decisions.md`, `results/environment.json`

## 생성된 측정 결과 파일 경로

- 환경 정보: `results/environment.json`
- 성능 raw/summary와 EXPLAIN 결과: 아직 생성하지 않음(5단계 범위)
