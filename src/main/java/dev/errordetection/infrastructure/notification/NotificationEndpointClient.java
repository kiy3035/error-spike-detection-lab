package dev.errordetection.infrastructure.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NotificationEndpointClient {

    private final RestClient restClient;
    private final NotificationEndpointProperties properties;

    /**
     * 가짜 알림 endpoint 확인에 필요한 의존성을 주입합니다.
     *
     * @param notificationRestClient 가짜 알림 서버용 HTTP 클라이언트
     * @param properties endpoint 경로 설정
     */
    public NotificationEndpointClient(
            RestClient notificationRestClient,
            NotificationEndpointProperties properties
    ) {
        this.restClient = notificationRestClient;
        this.properties = properties;
    }

    /**
     * 가짜 알림 서버의 health endpoint가 2xx로 응답하는지 확인합니다.
     */
    public void verifyHealth() {
        restClient.get()
                .uri(properties.healthPath())
                .retrieve()
                .toBodilessEntity();
    }
}
