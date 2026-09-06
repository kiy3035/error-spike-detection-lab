package dev.errordetection.alert;

import dev.errordetection.counter.CounterProperties;
import dev.errordetection.counter.CounterResult;
import dev.errordetection.error.domain.ErrorEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AlertDispatchCoordinator {

    private final AlertProperties alertProperties;
    private final CounterProperties counterProperties;
    private final CooldownCoordinator cooldownCoordinator;
    private final AlertDeliveryService deliveryService;
    private final AsyncAlertSender alertSender;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /**
     * 임계값 판정부터 비동기 등록까지 필요한 별도 Bean들을 주입합니다.
     *
     * @param alertProperties 임계값과 cooldown 설정
     * @param counterProperties rolling window 설정
     * @param cooldownCoordinator 원자 cooldown 조정자
     * @param deliveryService 알림 이력 서비스
     * @param alertSender 비동기 알림 전송 Bean
     * @param clock 서버 기준 시계
     * @param meterRegistry 알림 metric 레지스트리
     */
    public AlertDispatchCoordinator(
            AlertProperties alertProperties,
            CounterProperties counterProperties,
            CooldownCoordinator cooldownCoordinator,
            AlertDeliveryService deliveryService,
            AsyncAlertSender alertSender,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.alertProperties = alertProperties;
        this.counterProperties = counterProperties;
        this.cooldownCoordinator = cooldownCoordinator;
        this.deliveryService = deliveryService;
        this.alertSender = alertSender;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * count가 임계값 이상이면 cooldown을 선점하고 PENDING 저장 뒤 비동기 전송을 등록합니다.
     *
     * @param event 방금 commit된 에러 이벤트
     * @param counter rolling count 결과
     * @return 임계값, cooldown, 비동기 등록 결과
     */
    public AlertDecision dispatchIfNeeded(ErrorEvent event, CounterResult counter) {
        if (counter.count() < alertProperties.threshold()) {
            return new AlertDecision(false, false, false);
        }

        UUID alertId = UUID.randomUUID();
        var cooldown = cooldownCoordinator.acquire(event.getSource(), alertId, counter.path());
        if (!cooldown.acquired()) {
            meterRegistry.counter("error.alerts", "result", "suppressed").increment();
            return new AlertDecision(true, false, false);
        }

        Instant detectedAt = clock.instant();
        deliveryService.createPending(alertId, event, counter, cooldown.path(), detectedAt);
        alertSender.send(new AlertPayload(
                alertId,
                event.getSource(),
                counter.count(),
                counterProperties.windowSeconds(),
                detectedAt
        ));
        meterRegistry.counter("error.alerts", "result", "queued").increment();
        return new AlertDecision(true, true, true);
    }

    public record AlertDecision(
            boolean thresholdExceeded,
            boolean cooldownAcquired,
            boolean alertQueued
    ) {
    }
}
