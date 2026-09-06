package dev.errordetection.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.errordetection.error.domain.ErrorEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class RollingErrorCounterTest {

    @Mock
    private RedisBucketCounter redisCounter;

    @Mock
    private ErrorEventRepository repository;

    private RollingErrorCounter counter;

    /**
     * 각 테스트 전에 60초 window와 독립 metric 레지스트리를 구성합니다.
     */
    @BeforeEach
    void setUp() {
        counter = new RollingErrorCounter(
                redisCounter,
                repository,
                new CounterProperties(60, 120),
                new SimpleMeterRegistry()
        );
    }

    /**
     * Redis 정상 결과를 DB 조회 없이 반환하는지 확인합니다.
     */
    @Test
    void returnsRedisCountOnNormalPath() {
        Instant end = Instant.parse("2026-09-06T00:00:00Z");
        when(redisCounter.incrementAndCount("order-api", end)).thenReturn(7L);

        CounterResult result = counter.incrementAndCount("order-api", end);

        assertThat(result.count()).isEqualTo(7L);
        assertThat(result.path()).isEqualTo(CounterPath.REDIS);
    }

    /**
     * Redis 연결 실패에만 동일 시간 범위 DB count를 사용하는지 확인합니다.
     */
    @Test
    void fallsBackToDatabaseOnConnectionFailure() {
        Instant end = Instant.parse("2026-09-06T00:00:00Z");
        Instant start = end.minusSeconds(60);
        when(redisCounter.incrementAndCount("order-api", end))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(repository.countBySourceAndReceivedAtGreaterThanAndReceivedAtLessThanEqual(
                "order-api",
                start,
                end
        )).thenReturn(3L);

        CounterResult result = counter.incrementAndCount("order-api", end);

        assertThat(result.count()).isEqualTo(3L);
        assertThat(result.path()).isEqualTo(CounterPath.DB_FALLBACK);
        verify(repository).countBySourceAndReceivedAtGreaterThanAndReceivedAtLessThanEqual(
                "order-api",
                start,
                end
        );
    }

    /**
     * 프로그래밍 오류를 DB fallback으로 숨기지 않는지 확인합니다.
     */
    @Test
    void propagatesUnexpectedProgrammingError() {
        Instant end = Instant.parse("2026-09-06T00:00:00Z");
        when(redisCounter.incrementAndCount("order-api", end))
                .thenThrow(new IllegalStateException("unexpected"));

        assertThatThrownBy(() -> counter.incrementAndCount("order-api", end))
                .isInstanceOf(IllegalStateException.class);
    }
}
