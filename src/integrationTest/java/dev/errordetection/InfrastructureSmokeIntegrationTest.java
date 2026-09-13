package dev.errordetection;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.errordetection.alert.AlertDeliveryRepository;
import dev.errordetection.alert.AlertStatus;
import dev.errordetection.alert.DbCooldownGate;
import dev.errordetection.alert.RedisCooldownGate;
import dev.errordetection.infrastructure.notification.NotificationEndpointClient;
import dev.errordetection.counter.RedisBucketCounter;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureSmokeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.8-alpine")
    );

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.2-alpine")
    ).withExposedPorts(6379);

    private static final WireMockServer WIRE_MOCK = new WireMockServer(0);

    static {
        WIRE_MOCK.start();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/mock/health"))
                .willReturn(okJson("{\"status\":\"UP\"}")));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private NotificationEndpointClient notificationEndpointClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RedisBucketCounter redisBucketCounter;

    @Autowired
    private RedisCooldownGate redisCooldownGate;

    @Autowired
    private DbCooldownGate dbCooldownGate;

    @Autowired
    private AlertDeliveryRepository alertDeliveryRepository;

    /**
     * Testcontainers와 WireMock의 동적 연결 정보를 Spring 설정에 주입합니다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void registerInfrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/local");
        registry.add("app.notification.base-url", WIRE_MOCK::baseUrl);
        registry.add("app.notification.read-timeout", () -> "250ms");
        registry.add("app.alert.threshold", () -> 3);
        registry.add("app.alert.cooldown-seconds", () -> 1);
        registry.add("management.endpoint.health.show-details", () -> "always");
    }

    /**
     * 테스트마다 WireMock journal을 비우고 health 및 기본 성공 endpoint를 다시 구성합니다.
     */
    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/mock/health"))
                .willReturn(okJson("{\"status\":\"UP\"}")));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/mock/alerts"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(202)));
    }

    /**
     * PostgreSQL, Redis, WireMock과 Actuator health가 한 애플리케이션에서 함께 동작하는지 확인합니다.
     */
    @Test
    void verifiesDatabaseRedisWireMockAndActuatorHealth() {
        Integer seedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM error_event WHERE run_id = 'stage1-smoke'",
                Integer.class
        );
        assertThat(seedCount).isEqualTo(1);

        redisTemplate.opsForValue().set("stage1:smoke", "PONG");
        assertThat(redisTemplate.opsForValue().get("stage1:smoke")).isEqualTo("PONG");
        redisTemplate.delete("stage1:smoke");

        notificationEndpointClient.verifyHealth();

        var response = restTemplate.getForEntity("/actuator/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    /**
     * 에러 저장 뒤 서버 수신 시각 기준 이력과 빈 구간을 포함한 추이를 조회합니다.
     */
    @Test
    void savesAndQueriesErrorHistoryAndTrend() {
        Instant occurredAt = Instant.now().minusSeconds(10);
        Map<String, Object> request = Map.of(
                "source", "order-api",
                "errorCode", "SYNTHETIC_TIMEOUT",
                "severity", "ERROR",
                "message", "synthetic integration timeout",
                "occurredAt", occurredAt.toString(),
                "traceId", "integration-trace-1",
                "runId", "stage2-integration"
        );

        var createResponse = restTemplate.postForEntity("/errors", request, Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) createResponse.getBody().get("counterDurationNanos")).longValue()).isPositive();
        Instant receivedAt = Instant.parse(createResponse.getBody().get("receivedAt").toString());

        Instant from = receivedAt.minusSeconds(60);
        Instant to = receivedAt.plusSeconds(60);
        URI historyUri = UriComponentsBuilder.fromPath("/errors")
                .queryParam("source", "order-api")
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("page", 0)
                .queryParam("size", 10)
                .build()
                .encode()
                .toUri();
        var historyResponse = restTemplate.getForEntity(historyUri, Map.class);
        assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyResponse.getBody().get("totalElements")).isEqualTo(1);

        URI trendUri = UriComponentsBuilder.fromPath("/errors/trend")
                .queryParam("source", "order-api")
                .queryParam("from", from.truncatedTo(ChronoUnit.MINUTES))
                .queryParam("to", to.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES))
                .queryParam("bucket", "MINUTE")
                .build()
                .encode()
                .toUri();
        var trendResponse = restTemplate.getForEntity(trendUri, Map.class);
        assertThat(trendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<?>) trendResponse.getBody().get("points")).hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * 허용 크기를 넘는 페이지 요청과 유효하지 않은 본문을 400으로 거부합니다.
     */
    @Test
    void rejectsInvalidInputAndOversizedPage() {
        Map<String, Object> invalidRequest = Map.of(
                "source", "invalid source",
                "errorCode", "INVALID-CODE",
                "severity", "ERROR",
                "message", "synthetic invalid request",
                "occurredAt", Instant.now().toString()
        );
        var createResponse = restTemplate.postForEntity("/errors", invalidRequest, Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        URI historyUri = UriComponentsBuilder.fromPath("/errors")
                .queryParam("source", "order-api")
                .queryParam("from", Instant.now().minusSeconds(60))
                .queryParam("to", Instant.now().plusSeconds(60))
                .queryParam("size", 101)
                .build()
                .encode()
                .toUri();
        var historyResponse = restTemplate.exchange(RequestEntity.get(historyUri).build(), Map.class);
        assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 최근 60개 1초 bucket 경계와 source 격리가 실제 Redis에서 유지되는지 확인합니다.
     */
    @Test
    void countsSixtyBucketsAndSeparatesSources() {
        Instant now = Instant.parse("2026-09-06T01:00:00Z");
        redisBucketCounter.incrementAndCount("boundary-api", now.minusSeconds(60));
        long boundaryCount = redisBucketCounter.incrementAndCount("boundary-api", now);
        long otherSourceCount = redisBucketCounter.incrementAndCount("other-api", now);

        assertThat(boundaryCount).isEqualTo(1L);
        assertThat(otherSourceCount).isEqualTo(1L);
    }

    /**
     * 같은 1초 bucket의 동시 100건 증가가 유실되지 않는지 확인합니다.
     *
     * @throws Exception 비동기 작업 실패 시 전달되는 예외
     */
    @Test
    void incrementsOneSecondBucketConcurrently() throws Exception {
        Instant now = Instant.parse("2026-09-06T02:00:00Z");
        try (var executor = Executors.newFixedThreadPool(12)) {
            var tasks = IntStream.range(0, 100)
                    .mapToObj(index -> (java.util.concurrent.Callable<Long>) () ->
                            redisBucketCounter.incrementAndCount("concurrent-api", now))
                    .toList();
            for (Future<Long> future : executor.invokeAll(tasks)) {
                future.get();
            }
        }

        long finalCount = redisBucketCounter.incrementAndCount("concurrent-api", now);
        assertThat(finalCount).isEqualTo(101L);
    }

    /**
     * Redis와 DB cooldown의 동시 요청에서 각각 한 요청만 선점하는지 확인합니다.
     *
     * @throws Exception 동시 작업 실패 시 전달되는 예외
     */
    @Test
    void allowsOneConcurrentWinnerForRedisAndDatabaseCooldown() throws Exception {
        assertThat(concurrentWinners(() -> redisCooldownGate.acquire(
                "redis-cooldown-api",
                java.util.UUID.randomUUID()
        ))).isEqualTo(1L);
        assertThat(concurrentWinners(() -> dbCooldownGate.acquire(
                "db-cooldown-api",
                java.util.UUID.randomUUID()
        ))).isEqualTo(1L);
    }

    /**
     * 세 번째 에러에서 알림 한 건이 비동기로 성공하고 조회 API에 기록되는지 확인합니다.
     */
    @Test
    void sendsOneAlertAsynchronouslyAtThreshold() {
        String runId = "stage4-e2e";
        postErrors("e2e-api", runId, 3);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var deliveries = alertDeliveryRepository.findAll().stream()
                    .filter(delivery -> runId.equals(delivery.getRunId()))
                    .toList();
            assertThat(deliveries).singleElement()
                    .satisfies(delivery -> {
                        assertThat(delivery.getStatus()).isEqualTo(AlertStatus.SENT);
                        assertThat(delivery.getAttemptCount()).isEqualTo(1);
                    });
        });
        WIRE_MOCK.verify(exactly(1), requestPatternForSource("e2e-api"));

        var response = restTemplate.getForEntity("/alerts?runId=" + runId, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalElements", 1);
    }

    /**
     * WireMock 500, 500, 200 순서에서 세 번째 시도에 SENT가 되는지 확인합니다.
     */
    @Test
    void retriesTransientServerErrorsUntilSuccess() {
        String scenario = "retry-then-success";
        WIRE_MOCK.stubFor(stubForSource("retry-api")
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("SECOND")
                .willReturn(aResponse().withStatus(500)));
        WIRE_MOCK.stubFor(stubForSource("retry-api")
                .inScenario(scenario)
                .whenScenarioStateIs("SECOND")
                .willSetStateTo("SUCCESS")
                .willReturn(aResponse().withStatus(500)));
        WIRE_MOCK.stubFor(stubForSource("retry-api")
                .inScenario(scenario)
                .whenScenarioStateIs("SUCCESS")
                .willReturn(aResponse().withStatus(200)));

        String runId = "stage4-retry";
        postErrors("retry-api", runId, 3);

        awaitStatus(runId, AlertStatus.SENT, 3);
        WIRE_MOCK.verify(exactly(3), requestPatternForSource("retry-api"));
    }

    /**
     * 일반 4xx는 재시도하지 않고 첫 시도 뒤 FAILED가 되는지 확인합니다.
     */
    @Test
    void doesNotRetryPermanentClientError() {
        WIRE_MOCK.stubFor(stubForSource("permanent-api")
                .atPriority(1)
                .willReturn(aResponse().withStatus(400)));

        String runId = "stage4-permanent";
        postErrors("permanent-api", runId, 3);

        awaitStatus(runId, AlertStatus.FAILED, 1);
        WIRE_MOCK.verify(exactly(1), requestPatternForSource("permanent-api"));
    }

    /**
     * 마지막까지 5xx이면 세 번 시도 뒤 Recover가 FAILED를 기록하는지 확인합니다.
     */
    @Test
    void marksFailedAfterTransientRetriesAreExhausted() {
        WIRE_MOCK.stubFor(stubForSource("failed-api")
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));

        String runId = "stage4-failed";
        postErrors("failed-api", runId, 3);

        awaitStatus(runId, AlertStatus.FAILED, 3);
        WIRE_MOCK.verify(exactly(3), requestPatternForSource("failed-api"));
    }

    /**
     * HTTP read timeout도 일시적 오류로 분류해 세 번 시도 뒤 FAILED가 되는지 확인합니다.
     */
    @Test
    void retriesReadTimeoutAndMarksFailed() {
        WIRE_MOCK.stubFor(stubForSource("timeout-alert-api")
                .atPriority(1)
                .willReturn(aResponse().withStatus(202).withFixedDelay(500)));

        String runId = "stage4-alert-timeout";
        postErrors("timeout-alert-api", runId, 3);

        awaitStatus(runId, AlertStatus.FAILED, 3);
        WIRE_MOCK.verify(exactly(3), requestPatternForSource("timeout-alert-api"));
    }

    /**
     * 설정한 TTL이 지나면 Redis와 DB cooldown 모두 다시 선점 가능한지 확인합니다.
     */
    @Test
    void reacquiresRedisAndDatabaseCooldownAfterExpiry() {
        String redisSource = "redis-expiry-api";
        String dbSource = "db-expiry-api";
        assertThat(redisCooldownGate.acquire(redisSource, java.util.UUID.randomUUID())).isTrue();
        assertThat(dbCooldownGate.acquire(dbSource, java.util.UUID.randomUUID())).isTrue();

        await().atMost(3, TimeUnit.SECONDS).pollDelay(1200, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(redisCooldownGate.acquire(redisSource, java.util.UUID.randomUUID())).isTrue();
            assertThat(dbCooldownGate.acquire(dbSource, java.util.UUID.randomUUID())).isTrue();
        });
    }

    /**
     * 동일 gate에 50개 동시 호출을 실행해 성공 횟수를 반환합니다.
     *
     * @param operation cooldown 선점 호출
     * @return 선점 성공 횟수
     * @throws Exception 동시 작업 실패 시 전달되는 예외
     */
    private long concurrentWinners(java.util.concurrent.Callable<Boolean> operation) throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            var tasks = IntStream.range(0, 50)
                    .mapToObj(index -> operation)
                    .toList();
            long winners = 0;
            for (Future<Boolean> future : executor.invokeAll(tasks)) {
                if (future.get()) {
                    winners++;
                }
            }
            return winners;
        }
    }

    /**
     * 지정한 source와 runId로 합성 에러를 반복 전송합니다.
     *
     * @param source 에러 발생원
     * @param runId 실행 식별자
     * @param count 전송 건수
     */
    private void postErrors(String source, String runId, int count) {
        for (int index = 0; index < count; index++) {
            var response = restTemplate.postForEntity("/errors", request(source, runId, index), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    /**
     * logical alert가 기대 상태와 시도 횟수에 도달할 때까지 기다립니다.
     *
     * @param runId 실행 식별자
     * @param status 기대 상태
     * @param attempts 기대 시도 횟수
     */
    private void awaitStatus(String runId, AlertStatus status, int attempts) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var deliveries = alertDeliveryRepository.findAll().stream()
                    .filter(delivery -> runId.equals(delivery.getRunId()))
                    .toList();
            assertThat(deliveries).singleElement()
                    .satisfies(delivery -> {
                        assertThat(delivery.getStatus()).isEqualTo(status);
                        assertThat(delivery.getAttemptCount()).isEqualTo(attempts);
                    });
        });
    }

    /**
     * source가 일치하는 알림 POST 요청 패턴을 만듭니다.
     *
     * @param source 에러 발생원
     * @return WireMock 요청 패턴
     */
    private com.github.tomakehurst.wiremock.client.MappingBuilder stubForSource(String source) {
        return post(urlPathEqualTo("/mock/alerts"))
                .withRequestBody(matchingJsonPath("$.source", equalTo(source)));
    }

    /**
     * source가 일치하는 알림 POST 검증 패턴을 만듭니다.
     *
     * @param source 에러 발생원
     * @return WireMock 요청 검증 패턴
     */
    private com.github.tomakehurst.wiremock.matching.RequestPatternBuilder requestPatternForSource(String source) {
        return postRequestedFor(urlPathEqualTo("/mock/alerts"))
                .withRequestBody(matchingJsonPath("$.source", equalTo(source)));
    }

    /**
     * 통합 테스트용 합성 에러 요청을 만듭니다.
     *
     * @param source 에러 발생원
     * @param runId 실행 식별자
     * @param index 요청 순번
     * @return 합성 에러 요청
     */
    private Map<String, Object> request(String source, String runId, int index) {
        return Map.of(
                "source", source,
                "errorCode", "SYNTHETIC_FAILURE",
                "severity", "ERROR",
                "message", "synthetic alert event",
                "occurredAt", Instant.now().minusSeconds(1).toString(),
                "traceId", runId + "-" + index,
                "runId", runId
        );
    }

    /**
     * 통합 테스트가 만든 로컬 WireMock 서버를 종료합니다.
     */
    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }
}
