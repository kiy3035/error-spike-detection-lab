package dev.errordetection.infrastructure.notification;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class NotificationEndpointHealthIndicator implements HealthIndicator {

    private final NotificationEndpointClient client;

    /**
     * 가짜 알림 endpoint 상태 확인 클라이언트를 주입합니다.
     *
     * @param client 가짜 알림 endpoint 클라이언트
     */
    public NotificationEndpointHealthIndicator(NotificationEndpointClient client) {
        this.client = client;
    }

    /**
     * 가짜 알림 endpoint 연결 결과를 Actuator health 상태로 변환합니다.
     *
     * @return endpoint가 응답하면 UP, 예외가 발생하면 DOWN인 상태
     */
    @Override
    public Health health() {
        try {
            client.verifyHealth();
            return Health.up().withDetail("endpoint", "local-wiremock").build();
        } catch (RuntimeException exception) {
            return Health.down(exception).withDetail("endpoint", "local-wiremock").build();
        }
    }
}
