package dev.errordetection.alert;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertQueryService {

    private static final Duration MAX_RANGE = Duration.ofDays(7);

    private final AlertDeliveryRepository repository;

    /**
     * 알림 이력 조회 저장소를 주입합니다.
     *
     * @param repository 알림 이력 저장소
     */
    public AlertQueryService(AlertDeliveryRepository repository) {
        this.repository = repository;
    }

    /**
     * 선택 조건과 최대 7일 범위로 알림 이력을 페이지 조회합니다.
     *
     * @param runId 실험 실행 식별자
     * @param source 에러 발생원
     * @param status 알림 상태
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 알림 이력 페이지
     */
    @Transactional(readOnly = true)
    public AlertHistoryResponse history(
            String runId,
            String source,
            AlertStatus status,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        validateRange(from, to);
        var result = repository.findAll(
                specification(runId, source, status, from, to),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "queuedAt"))
        );
        return new AlertHistoryResponse(
                result.getContent().stream().map(AlertHistoryItem::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    /**
     * null이 아닌 조회 조건만 SQL predicate에 포함해 PostgreSQL null 타입 추론 문제를 피합니다.
     *
     * @param runId 실험 실행 식별자
     * @param source 에러 발생원
     * @param status 알림 상태
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @return 동적 조회 조건
     */
    private Specification<AlertDelivery> specification(
            String runId,
            String source,
            AlertStatus status,
            Instant from,
            Instant to
    ) {
        return (root, query, builder) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (runId != null) {
                predicates.add(builder.equal(root.get("runId"), runId));
            }
            if (source != null) {
                predicates.add(builder.equal(root.get("source"), source));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("queuedAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThan(root.get("queuedAt"), to));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    /**
     * 조회 시각 조건의 순서와 최대 범위를 검증합니다.
     *
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     */
    private void validateRange(Instant from, Instant to) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from과 to는 함께 지정해야 합니다.");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from은 to보다 앞서야 합니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("알림 조회 범위는 최대 7일입니다.");
        }
    }
}
