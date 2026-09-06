package dev.errordetection.error.application;

import dev.errordetection.error.api.ErrorHistoryItem;
import dev.errordetection.error.api.ErrorHistoryResponse;
import dev.errordetection.error.api.ErrorTrendResponse;
import dev.errordetection.error.api.TrendBucket;
import dev.errordetection.error.api.TrendPoint;
import dev.errordetection.error.domain.ErrorEvent;
import dev.errordetection.error.domain.ErrorEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ErrorQueryService {

    private static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BUCKETS = 1_000;

    private final ErrorEventRepository repository;
    private final ErrorTrendRepository trendRepository;

    /**
     * 에러 이력과 추이 조회에 필요한 저장소를 주입합니다.
     *
     * @param repository 에러 이력 저장소
     * @param trendRepository 시간 구간 집계 저장소
     */
    public ErrorQueryService(ErrorEventRepository repository, ErrorTrendRepository trendRepository) {
        this.repository = repository;
        this.trendRepository = trendRepository;
    }

    /**
     * source와 서버 수신 시각 범위로 제한된 페이지의 에러 이력을 조회합니다.
     *
     * @param source 에러 발생원
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기
     * @return 에러 이력 페이지 응답
     */
    public ErrorHistoryResponse history(String source, Instant from, Instant to, int page, int size) {
        validateRange(from, to);
        if (page < 0) {
            throw new IllegalArgumentException("page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size는 1 이상 100 이하여야 합니다.");
        }

        var result = repository
                .findBySourceAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
                        source,
                        from,
                        to,
                        PageRequest.of(page, size)
                );
        List<ErrorHistoryItem> content = result.getContent().stream()
                .map(this::toHistoryItem)
                .toList();
        return new ErrorHistoryResponse(content, page, size, result.getTotalElements(), result.getTotalPages());
    }

    /**
     * source와 서버 수신 시각 범위를 집계하고 비어 있는 구간을 0으로 채웁니다.
     *
     * @param source 에러 발생원
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param bucket 집계 단위
     * @return 모든 구간을 포함한 추이 응답
     */
    public ErrorTrendResponse trend(String source, Instant from, Instant to, TrendBucket bucket) {
        validateRange(from, to);
        ChronoUnit unit = bucket == TrendBucket.MINUTE ? ChronoUnit.MINUTES : ChronoUnit.HOURS;
        Instant firstBucket = from.truncatedTo(unit);
        long bucketCount = unit.between(firstBucket, to.minusNanos(1)) + 1;
        if (bucketCount > MAX_BUCKETS) {
            throw new IllegalArgumentException("조회 결과 bucket 수는 1000개 이하여야 합니다.");
        }

        Map<Instant, Long> counts = trendRepository.aggregate(source, from, to, bucket);
        List<TrendPoint> points = new ArrayList<>((int) bucketCount);
        for (Instant cursor = firstBucket; cursor.isBefore(to); cursor = cursor.plus(1, unit)) {
            points.add(new TrendPoint(cursor, counts.getOrDefault(cursor, 0L)));
        }
        return new ErrorTrendResponse(source, from, to, bucket, List.copyOf(points));
    }

    /**
     * 시간 범위의 순서와 최대 조회 기간을 검증합니다.
     *
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     */
    private void validateRange(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from은 to보다 이전이어야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("조회 기간은 7일 이하여야 합니다.");
        }
    }

    /**
     * 영속 엔티티를 외부 이력 응답 항목으로 변환합니다.
     *
     * @param event 에러 엔티티
     * @return 에러 이력 응답 항목
     */
    private ErrorHistoryItem toHistoryItem(ErrorEvent event) {
        return new ErrorHistoryItem(
                event.getId(),
                event.getSource(),
                event.getErrorCode(),
                event.getSeverity(),
                event.getMessage(),
                event.getOccurredAt(),
                event.getReceivedAt(),
                event.getTraceId(),
                event.getRunId()
        );
    }
}
