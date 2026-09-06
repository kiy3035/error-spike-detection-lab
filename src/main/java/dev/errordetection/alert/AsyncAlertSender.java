package dev.errordetection.alert;

import dev.errordetection.infrastructure.notification.NotificationEndpointProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class AsyncAlertSender {

    private final RestClient notificationRestClient;
    private final NotificationEndpointProperties endpointProperties;
    private final AlertDeliveryService deliveryService;
    private final MeterRegistry meterRegistry;

    /**
     * 비동기 재시도 전송에 필요한 HTTP 클라이언트와 상태 서비스를 주입합니다.
     *
     * @param notificationRestClient 로컬 WireMock HTTP 클라이언트
     * @param endpointProperties 알림 endpoint 설정
     * @param deliveryService 별도 트랜잭션 상태 서비스
     * @param meterRegistry 전송 시간 metric 레지스트리
     */
    public AsyncAlertSender(
            RestClient notificationRestClient,
            NotificationEndpointProperties endpointProperties,
            AlertDeliveryService deliveryService,
            MeterRegistry meterRegistry
    ) {
        this.notificationRestClient = notificationRestClient;
        this.endpointProperties = endpointProperties;
        this.deliveryService = deliveryService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * bounded executor에서 WireMock으로 전송하고 연결 오류, timeout, 429와 5xx만 재시도합니다.
     *
     * @param payload logical alert payload
     */
    @Async("alertExecutor")
    @Retryable(
            retryFor = TransientAlertException.class,
            maxAttemptsExpression = "${app.alert.retry.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${app.alert.retry.initial-delay-ms}",
                    multiplierExpression = "${app.alert.retry.multiplier}"
            )
    )
    public void send(AlertPayload payload) {
        deliveryService.recordAttempt(payload.alertId());
        var sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
        try {
            notificationRestClient.post()
                    .uri(endpointProperties.alertPath())
                    .header("Idempotency-Key", payload.alertId().toString())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            deliveryService.markSent(payload.alertId());
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new TransientAlertException("HTTP_429", exception);
            }
            deliveryService.markFailed(payload.alertId(), "HTTP_4XX");
        } catch (HttpServerErrorException exception) {
            throw new TransientAlertException("HTTP_5XX", exception);
        } catch (ResourceAccessException exception) {
            throw new TransientAlertException("CONNECTION_OR_TIMEOUT", exception);
        } catch (RestClientException exception) {
            deliveryService.markFailed(payload.alertId(), "NON_RETRYABLE_CLIENT_ERROR");
        } finally {
            sample.stop(meterRegistry.timer("error.alert.delivery.duration"));
        }
    }

    /**
     * 일시적 오류 재시도를 모두 소진한 logical alert를 FAILED로 기록합니다.
     *
     * @param exception 마지막 재시도 가능 오류
     * @param payload logical alert payload
     */
    @Recover
    public void recover(TransientAlertException exception, AlertPayload payload) {
        deliveryService.markFailed(payload.alertId(), exception.getMessage());
    }

}
