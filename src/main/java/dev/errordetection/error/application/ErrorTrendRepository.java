package dev.errordetection.error.application;

import dev.errordetection.error.api.TrendBucket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ErrorTrendRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 시간 구간 집계에 사용할 JDBC 접근 도구를 주입합니다.
     *
     * @param jdbcTemplate JDBC 접근 도구
     */
    public ErrorTrendRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 서버 수신 시각을 허용된 PostgreSQL date_trunc 단위로 집계합니다.
     *
     * @param source 에러 발생원
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param bucket 허용된 집계 단위
     * @return 구간 시작 시각별 건수
     */
    public Map<Instant, Long> aggregate(String source, Instant from, Instant to, TrendBucket bucket) {
        String bucketUnit = bucket == TrendBucket.MINUTE ? "minute" : "hour";
        String sql = """
                SELECT date_trunc(?, received_at) AS bucket_start, COUNT(*) AS event_count
                FROM error_event
                WHERE source = ?
                  AND received_at >= ?
                  AND received_at < ?
                GROUP BY bucket_start
                ORDER BY bucket_start
                """;

        return jdbcTemplate.query(
                        sql,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getTimestamp("bucket_start").toInstant(),
                                resultSet.getLong("event_count")
                        ),
                        bucketUnit,
                        source,
                        Timestamp.from(from),
                        Timestamp.from(to)
                ).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
