package dev.errordetection;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisFallbackIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16.8-alpine")
    ).withNetwork(NETWORK);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.2-alpine")
    ).withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
    ).withNetwork(NETWORK);

    private static ToxiproxyContainer.ContainerProxy redisProxy;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * PostgreSQL과 Toxiproxy를 거친 Redis 연결 정보를 애플리케이션에 주입합니다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void registerInfrastructureProperties(DynamicPropertyRegistry registry) {
        redisProxy = TOXIPROXY.getProxy(REDIS, 6379);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", redisProxy::getContainerIpAddress);
        registry.add("spring.data.redis.port", redisProxy::getProxyPort);
        registry.add("spring.data.redis.timeout", () -> "100ms");
        registry.add("app.notification.base-url", () -> "http://localhost:1");
    }

    /**
     * Redis 응답이 100ms를 넘으면 같은 서버 수신 시각 범위를 DB에서 count하는지 확인합니다.
     *
     * @throws Exception Toxiproxy 제어 실패 시 전달되는 예외
     */
    @Test
    @Order(1)
    void fallsBackToDatabaseAfterRedisCommandTimeout() throws Exception {
        redisProxy.toxics().latency("redis-timeout", ToxicDirection.DOWNSTREAM, 300);

        var response = restTemplate.postForEntity(
                "/errors",
                request("timeout-api", "stage3-timeout"),
                Map.class
        );

        redisProxy.toxics().get("redis-timeout").remove();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("countPath", "DB_FALLBACK");
        assertThat(response.getBody()).containsEntry("rollingCount", 1);
    }

    /**
     * Redis 컨테이너 중단 시 연결 실패를 DB fallback으로 전환하는지 확인합니다.
     */
    @Test
    @Order(2)
    void fallsBackToDatabaseAfterRedisStops() {
        REDIS.stop();

        var response = restTemplate.postForEntity(
                "/errors",
                request("connection-api", "stage3-connection"),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("countPath", "DB_FALLBACK");
        assertThat(response.getBody()).containsEntry("rollingCount", 1);
    }

    /**
     * 장애 시나리오에 사용할 합성 에러 요청을 만듭니다.
     *
     * @param source 에러 발생원
     * @param runId 실행 식별자
     * @return 합성 에러 요청
     */
    private Map<String, Object> request(String source, String runId) {
        return Map.of(
                "source", source,
                "errorCode", "SYNTHETIC_FAILURE",
                "severity", "ERROR",
                "message", "synthetic fallback event",
                "occurredAt", Instant.now().minusSeconds(1).toString(),
                "traceId", runId + "-trace",
                "runId", runId
        );
    }
}
