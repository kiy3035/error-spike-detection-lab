package dev.errordetection.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.errordetection.counter.CounterPath;
import dev.errordetection.counter.CounterProperties;
import dev.errordetection.counter.CounterResult;
import dev.errordetection.error.domain.ErrorEvent;
import dev.errordetection.error.domain.ErrorSeverity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertDispatchCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");

    @Mock
    private CooldownCoordinator cooldownCoordinator;

    @Mock
    private AlertDeliveryService deliveryService;

    @Mock
    private AsyncAlertSender alertSender;

    private AlertDispatchCoordinator coordinator;
    private ErrorEvent event;

    /**
     * 임계값 100과 고정 시계를 사용하는 판정 서비스를 준비합니다.
     */
    @BeforeEach
    void setUp() {
        var properties = new AlertProperties(
                100,
                60,
                true,
                new AlertProperties.Retry(3, 100, 2.0),
                new AlertProperties.Executor(2, 4, 200)
        );
        coordinator = new AlertDispatchCoordinator(
                properties,
                new CounterProperties(60, 120),
                cooldownCoordinator,
                deliveryService,
                alertSender,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry()
        );
        event = new ErrorEvent(
                "order-api",
                "SYNTHETIC_TIMEOUT",
                ErrorSeverity.ERROR,
                "synthetic error",
                NOW.minusSeconds(1),
                NOW,
                "trace-1",
                "unit-run"
        );
    }

    /**
     * 임계값 직전인 99건에서는 cooldown과 전송을 호출하지 않는지 확인합니다.
     */
    @Test
    void doesNotQueueAtNinetyNine() {
        var decision = coordinator.dispatchIfNeeded(
                event,
                new CounterResult(99, CounterPath.REDIS, 1)
        );

        assertThat(decision.thresholdExceeded()).isFalse();
        assertThat(decision.alertQueued()).isFalse();
        verify(cooldownCoordinator, never()).acquire(any(), any(), any());
        verify(alertSender, never()).send(any());
    }

    /**
     * 임계값에 도달한 100건에서 선점 성공 시 PENDING 저장과 비동기 전송을 등록하는지 확인합니다.
     */
    @Test
    void queuesAtThreshold() {
        when(cooldownCoordinator.acquire(eq("order-api"), any(), eq(CounterPath.REDIS)))
                .thenReturn(new CooldownCoordinator.CooldownResult(true, CooldownPath.REDIS));

        var decision = coordinator.dispatchIfNeeded(
                event,
                new CounterResult(100, CounterPath.REDIS, 1)
        );

        assertThat(decision.thresholdExceeded()).isTrue();
        assertThat(decision.cooldownAcquired()).isTrue();
        assertThat(decision.alertQueued()).isTrue();
        verify(deliveryService).createPending(any(), eq(event), any(), eq(CooldownPath.REDIS), eq(NOW));
        verify(alertSender).send(any(AlertPayload.class));
    }

    /**
     * 임계값 초과 뒤 cooldown 선점에 실패하면 알림을 만들지 않는지 확인합니다.
     */
    @Test
    void suppressesAfterCooldownLoss() {
        when(cooldownCoordinator.acquire(eq("order-api"), any(), eq(CounterPath.REDIS)))
                .thenReturn(new CooldownCoordinator.CooldownResult(false, CooldownPath.REDIS));

        var decision = coordinator.dispatchIfNeeded(
                event,
                new CounterResult(101, CounterPath.REDIS, 1)
        );

        assertThat(decision.thresholdExceeded()).isTrue();
        assertThat(decision.cooldownAcquired()).isFalse();
        verify(deliveryService, never()).createPending(any(), any(), any(), any(), any());
        verify(alertSender, never()).send(any());
    }
}
