package dev.errordetection.alert;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DbCooldownGate {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final AlertProperties properties;

    /**
     * DB 조건부 upsert cooldown에 필요한 의존성을 주입합니다.
     *
     * @param jdbcTemplate JDBC 접근 도구
     * @param clock 동일 시간 기준 시계
     * @param properties cooldown 설정
     */
    public DbCooldownGate(JdbcTemplate jdbcTemplate, Clock clock, AlertProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * unique scope key와 조건부 upsert 한 문장으로 DB cooldown을 선점합니다.
     *
     * @param source 에러 발생원
     * @param alertId logical alert 식별자
     * @return INSERT 또는 만료 UPDATE가 성공했으면 true
     */
    public boolean acquire(String source, UUID alertId) {
        Instant now = clock.instant();
        Instant nextAllowedAt = now.plusSeconds(properties.cooldownSeconds());
        String sql = """
                INSERT INTO alert_cooldown(scope_key, next_allowed_at, token, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (scope_key) DO UPDATE
                SET next_allowed_at = EXCLUDED.next_allowed_at,
                    token = EXCLUDED.token,
                    updated_at = EXCLUDED.updated_at
                WHERE alert_cooldown.next_allowed_at <= EXCLUDED.updated_at
                RETURNING scope_key
                """;
        return !jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> resultSet.getString(1),
                source,
                Timestamp.from(nextAllowedAt),
                alertId,
                Timestamp.from(now)
        ).isEmpty();
    }
}
