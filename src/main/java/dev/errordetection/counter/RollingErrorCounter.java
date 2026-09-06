package dev.errordetection.counter;

import dev.errordetection.error.domain.ErrorEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import io.lettuce.core.RedisCommandTimeoutException;
import org.springframework.stereotype.Service;

@Service
public class RollingErrorCounter {

    private final RedisBucketCounter redisCounter;
    private final ErrorEventRepository repository;
    private final CounterProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * Redis 정상 경로와 DB fallback에 필요한 의존성을 주입합니다.
     *
     * @param redisCounter Redis bucket 카운터
     * @param repository DB 범위 count 저장소
     * @param properties window 설정
     * @param meterRegistry 경로별 측정 레지스트리
     */
    public RollingErrorCounter(
            RedisBucketCounter redisCounter,
            ErrorEventRepository repository,
            CounterProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.redisCounter = redisCounter;
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Redis rolling count를 우선 사용하고 연결 실패나 timeout에만 DB count로 전환합니다.
     *
     * @param source 에러 발생원
     * @param windowEnd 서버 수신 시각과 동일한 window 종료 시각
     * @return 건수, 실행 경로와 처리시간
     */
    public CounterResult incrementAndCount(String source, Instant windowEnd) {
        long startedAt = System.nanoTime();
        try {
            long count = redisCounter.incrementAndCount(source, windowEnd);
            return record(count, CounterPath.REDIS, startedAt);
        } catch (RedisConnectionFailureException exception) {
            incrementFallback("connection");
            return dbFallback(source, windowEnd, startedAt);
        } catch (QueryTimeoutException exception) {
            incrementFallback("timeout");
            return dbFallback(source, windowEnd, startedAt);
        } catch (RedisSystemException exception) {
            if (!hasTimeoutCause(exception)) {
                throw exception;
            }
            incrementFallback("timeout");
            return dbFallback(source, windowEnd, startedAt);
        }
    }

    /**
     * Redis 시스템 예외의 원인 체인에 실제 명령 timeout이 있는지 확인합니다.
     *
     * @param exception Redis 시스템 예외
     * @return 명령 timeout 원인이 있으면 true
     */
    private boolean hasTimeoutCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisCommandTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Redis와 동일한 종료 시각으로 최근 window 범위를 DB에서 count합니다.
     *
     * @param source 에러 발생원
     * @param windowEnd 포함 종료 시각
     * @param startedAt 전체 counter 시작 monotonic 시각
     * @return DB fallback 결과
     */
    private CounterResult dbFallback(String source, Instant windowEnd, long startedAt) {
        Instant windowStart = windowEnd.minus(Duration.ofSeconds(properties.windowSeconds()));
        long count = repository.countBySourceAndReceivedAtGreaterThanAndReceivedAtLessThanEqual(
                source,
                windowStart,
                windowEnd
        );
        return record(count, CounterPath.DB_FALLBACK, startedAt);
    }

    /**
     * 실행 경로별 counter 요청과 처리시간을 기록합니다.
     *
     * @param count 계산 건수
     * @param path 실행 경로
     * @param startedAt 시작 monotonic 시각
     * @return 측정값을 포함한 결과
     */
    private CounterResult record(long count, CounterPath path, long startedAt) {
        long durationNanos = System.nanoTime() - startedAt;
        String pathTag = path == CounterPath.REDIS ? "redis" : "db_fallback";
        Counter.builder("error.counter.requests").tag("path", pathTag).register(meterRegistry).increment();
        Timer.builder("error.counter.duration").tag("path", pathTag).register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        return new CounterResult(count, path, durationNanos);
    }

    /**
     * 허용된 Redis 가용성 실패 사유를 저카디널리티 metric으로 기록합니다.
     *
     * @param reason connection 또는 timeout
     */
    private void incrementFallback(String reason) {
        Counter.builder("error.counter.fallbacks").tag("reason", reason).register(meterRegistry).increment();
    }
}
