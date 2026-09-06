package dev.errordetection.alert;

import dev.errordetection.counter.CounterResult;
import dev.errordetection.error.domain.ErrorEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertDeliveryService {

    private final AlertDeliveryRepository repository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /**
     * 짧은 별도 트랜잭션으로 알림 상태를 갱신할 의존성을 주입합니다.
     *
     * @param repository 알림 이력 저장소
     * @param clock 서버 기준 시계
     * @param meterRegistry 알림 metric 레지스트리
     */
    public AlertDeliveryService(
            AlertDeliveryRepository repository,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * cooldown을 선점한 logical alert를 PENDING 상태로 저장합니다.
     *
     * @param alertId idempotency key
     * @param event 임계값을 만족시킨 에러 이벤트
     * @param counter rolling count 결과
     * @param cooldownPath cooldown 선점 경로
     * @param detectedAt 임계값 판정 시각
     * @return 저장된 알림 이력
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AlertDelivery createPending(
            UUID alertId,
            ErrorEvent event,
            CounterResult counter,
            CooldownPath cooldownPath,
            Instant detectedAt
    ) {
        Instant queuedAt = clock.instant();
        return repository.save(new AlertDelivery(
                alertId,
                event.getSource(),
                event.getRunId(),
                counter.count(),
                counter.path(),
                cooldownPath,
                event.getReceivedAt(),
                detectedAt,
                queuedAt
        ));
    }

    /**
     * 실제 HTTP 시도 직전에 시도 횟수와 최초 시도 시각을 기록합니다.
     *
     * @param alertId logical alert 식별자
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(UUID alertId) {
        findRequired(alertId).recordAttempt(clock.instant());
    }

    /**
     * 성공 응답을 받은 알림을 SENT로 전환하고 성공 metric을 기록합니다.
     *
     * @param alertId logical alert 식별자
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID alertId) {
        findRequired(alertId).markSent(clock.instant());
        meterRegistry.counter("error.alerts", "result", "sent").increment();
    }

    /**
     * 최종 실패한 알림을 정제된 분류와 함께 FAILED로 전환합니다.
     *
     * @param alertId logical alert 식별자
     * @param errorCategory 민감정보를 포함하지 않는 오류 분류
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID alertId, String errorCategory) {
        findRequired(alertId).markFailed(errorCategory);
        meterRegistry.counter("error.alerts", "result", "failed").increment();
    }

    /**
     * 필수 알림 이력을 조회하고 없으면 프로그래밍 오류로 중단합니다.
     *
     * @param alertId logical alert 식별자
     * @return 알림 이력
     */
    private AlertDelivery findRequired(UUID alertId) {
        return repository.findById(alertId)
                .orElseThrow(() -> new IllegalStateException("alert delivery not found"));
    }
}
