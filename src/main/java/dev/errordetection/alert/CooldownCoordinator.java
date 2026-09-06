package dev.errordetection.alert;

import dev.errordetection.counter.CounterPath;
import io.lettuce.core.RedisCommandTimeoutException;
import java.util.UUID;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;

@Service
public class CooldownCoordinator {

    private final RedisCooldownGate redisGate;
    private final DbCooldownGate dbGate;
    private final AlertProperties properties;

    /**
     * Redis/DB cooldown gate와 활성화 설정을 주입합니다.
     *
     * @param redisGate Redis cooldown gate
     * @param dbGate DB cooldown gate
     * @param properties cooldown 설정
     */
    public CooldownCoordinator(
            RedisCooldownGate redisGate,
            DbCooldownGate dbGate,
            AlertProperties properties
    ) {
        this.redisGate = redisGate;
        this.dbGate = dbGate;
        this.properties = properties;
    }

    /**
     * count 경로에 맞는 cooldown을 선점하고 장애 전환 시 DB gate를 사용합니다.
     *
     * @param source 에러 발생원
     * @param alertId logical alert 식별자
     * @param countPath count 실행 경로
     * @return 선점 여부와 실제 경로
     */
    public CooldownResult acquire(String source, UUID alertId, CounterPath countPath) {
        if (!properties.cooldownEnabled()) {
            return new CooldownResult(true, CooldownPath.DISABLED);
        }
        if (countPath == CounterPath.DB_FALLBACK) {
            return new CooldownResult(dbGate.acquire(source, alertId), CooldownPath.DB);
        }
        try {
            return new CooldownResult(redisGate.acquire(source, alertId), CooldownPath.REDIS);
        } catch (RedisConnectionFailureException | QueryTimeoutException exception) {
            return new CooldownResult(dbGate.acquire(source, alertId), CooldownPath.DB);
        } catch (RedisSystemException exception) {
            if (!hasTimeoutCause(exception)) {
                throw exception;
            }
            return new CooldownResult(dbGate.acquire(source, alertId), CooldownPath.DB);
        }
    }

    /**
     * Redis 시스템 예외의 원인 체인에서 실제 명령 timeout을 찾습니다.
     *
     * @param exception Redis 시스템 예외
     * @return timeout 원인이 있으면 true
     */
    private boolean hasTimeoutCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisCommandTimeoutException) {
                return true;
            }
        }
        return false;
    }

    public record CooldownResult(boolean acquired, CooldownPath path) {
    }
}
