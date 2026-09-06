package dev.errordetection.alert;

import dev.errordetection.counter.SourceKeyEncoder;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCooldownGate {

    private final StringRedisTemplate redisTemplate;
    private final SourceKeyEncoder keyEncoder;
    private final AlertProperties properties;

    /**
     * Redis 원자 cooldown 선점에 필요한 의존성을 주입합니다.
     *
     * @param redisTemplate 공유 Redis 템플릿
     * @param keyEncoder source 키 인코더
     * @param properties cooldown 설정
     */
    public RedisCooldownGate(
            StringRedisTemplate redisTemplate,
            SourceKeyEncoder keyEncoder,
            AlertProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.keyEncoder = keyEncoder;
        this.properties = properties;
    }

    /**
     * SET NX EX 한 번으로 source cooldown을 선점합니다.
     *
     * @param source 에러 발생원
     * @param alertId logical alert 식별자
     * @return 이번 요청이 선점했으면 true
     */
    public boolean acquire(String source, UUID alertId) {
        String key = "error:cooldown:{" + keyEncoder.encode(source) + "}";
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key,
                alertId.toString(),
                Duration.ofSeconds(properties.cooldownSeconds())
        );
        return Boolean.TRUE.equals(acquired);
    }
}
