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
