package dev.errordetection.counter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisBucketCounter {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return value
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final CounterProperties properties;
    private final SourceKeyEncoder keyEncoder;

    /**
     * Redis 1초 bucket 처리에 필요한 의존성을 주입합니다.
     *
     * @param redisTemplate 공유 Redis 연결 템플릿
     * @param properties window와 TTL 설정
     * @param keyEncoder source 키 인코더
     */
    public RedisBucketCounter(
            StringRedisTemplate redisTemplate,
            CounterProperties properties,
            SourceKeyEncoder keyEncoder
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.keyEncoder = keyEncoder;
    }

    /**
     * 현재 초 bucket을 원자 증가시키고 최근 N개 1초 bucket을 합산합니다.
     *
     * @param source 에러 발생원
     * @param receivedAt 서버 수신 시각
     * @return 1초 해상도 rolling window 건수
     */
    public long incrementAndCount(String source, Instant receivedAt) {
        long epochSecond = receivedAt.getEpochSecond();
        String encodedSource = keyEncoder.encode(source);
        String currentKey = bucketKey(encodedSource, epochSecond);
        Long incremented = redisTemplate.execute(
                INCREMENT_SCRIPT,
                List.of(currentKey),
                Integer.toString(properties.bucketTtlSeconds())
        );
        if (incremented == null || incremented < 0) {
            throw new IllegalStateException("Redis bucket 증가 결과가 유효하지 않습니다.");
        }

        List<String> keys = new ArrayList<>(properties.windowSeconds());
        for (int offset = properties.windowSeconds() - 1; offset >= 0; offset--) {
            keys.add(bucketKey(encodedSource, epochSecond - offset));
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            throw new IllegalStateException("Redis bucket 합산 결과가 없습니다.");
        }

        long sum = 0L;
        for (String value : values) {
            if (value != null) {
                long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    throw new IllegalStateException("Redis bucket 값은 음수일 수 없습니다.");
                }
                sum = Math.addExact(sum, parsed);
            }
        }
        return sum;
    }

    /**
     * source hash와 epoch second로 충돌 없는 bucket 키를 만듭니다.
     *
     * @param encodedSource source SHA-256 문자열
     * @param epochSecond bucket 초
     * @return Redis bucket 키
     */
    private String bucketKey(String encodedSource, long epochSecond) {
        return "error:count:{" + encodedSource + "}:" + epochSecond;
    }
}
