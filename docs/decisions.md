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
