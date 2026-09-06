package dev.errordetection.error.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.errordetection.error.api.TrendBucket;
import dev.errordetection.error.domain.ErrorEventRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ErrorQueryServiceTest {

    @Mock
    private ErrorEventRepository repository;

    @Mock
    private ErrorTrendRepository trendRepository;

    @InjectMocks
    private ErrorQueryService service;

    /**
     * 역전된 조회 시간 범위를 거부하는지 확인합니다.
     */
    @Test
    void rejectsReversedRange() {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");

        assertThatThrownBy(() -> service.history("order-api", now, now.minusSeconds(1), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 분 단위 결과가 1000개를 넘는 조회를 거부하는지 확인합니다.
     */
    @Test
    void rejectsTooManyTrendBuckets() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = from.plusSeconds(1_001L * 60L);

        assertThatThrownBy(() -> service.trend("order-api", from, to, TrendBucket.MINUTE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
