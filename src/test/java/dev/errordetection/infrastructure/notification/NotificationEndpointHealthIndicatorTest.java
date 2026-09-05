package dev.errordetection.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;

@ExtendWith(MockitoExtension.class)
class NotificationEndpointHealthIndicatorTest {

    @Mock
    private NotificationEndpointClient client;

    @InjectMocks
    private NotificationEndpointHealthIndicator healthIndicator;

    /**
     * 가짜 알림 endpoint가 응답하면 health 상태가 UP인지 확인합니다.
     */
    @Test
    void returnsUpWhenNotificationEndpointIsReachable() {
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    /**
     * 가짜 알림 endpoint 연결에 실패하면 health 상태가 DOWN인지 확인합니다.
     */
    @Test
    void returnsDownWhenNotificationEndpointIsUnavailable() {
        doThrow(new IllegalStateException("연결 실패")).when(client).verifyHealth();

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
