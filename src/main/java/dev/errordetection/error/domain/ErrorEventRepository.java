package dev.errordetection.error.domain;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorEventRepository extends JpaRepository<ErrorEvent, Long> {

    /**
     * source와 서버 수신 시각 범위로 에러 이력을 최신순 조회합니다.
     *
     * @param source 에러 발생원
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param pageable 페이지 조건
     * @return 에러 이력 페이지
     */
    Page<ErrorEvent> findBySourceAndReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtDesc(
            String source,
            Instant from,
            Instant to,
            Pageable pageable
    );

    /**
     * source와 서버 수신 시각 범위의 에러 건수를 계산합니다.
     *
     * @param source 에러 발생원
     * @param fromExclusive 제외 시작 시각
     * @param toInclusive 포함 종료 시각
     * @return 범위 내 에러 건수
     */
    long countBySourceAndReceivedAtGreaterThanAndReceivedAtLessThanEqual(
            String source,
            Instant fromExclusive,
            Instant toInclusive
    );
}
