package dev.errordetection;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.errordetection.infrastructure.notification.NotificationEndpointClient;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
        registry.add("management.endpoint.health.show-details", () -> "always");
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
     * 통합 테스트가 만든 로컬 WireMock 서버를 종료합니다.
     */
    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }
}
