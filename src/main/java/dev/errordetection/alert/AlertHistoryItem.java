package dev.errordetection.alert;

import dev.errordetection.counter.CounterPath;
import java.time.Instant;
import java.util.UUID;

public record AlertHistoryItem(
        UUID id,
        String source,
        String runId,
        long rollingCount,
        CounterPath countPath,
        CooldownPath cooldownPath,
        AlertStatus status,
        int attemptCount,
        Instant thresholdEventReceivedAt,
        Instant detectedAt,
        Instant queuedAt,
        Instant firstAttemptAt,
        Instant sentAt,
        String lastError
) {
    /**
     * 알림 엔티티를 외부 조회 응답으로 변환합니다.
     *
     * @param delivery 알림 이력 엔티티
     * @return 알림 조회 항목
     */
    public static AlertHistoryItem from(AlertDelivery delivery) {
        return new AlertHistoryItem(
                delivery.getId(),
                delivery.getSource(),
                delivery.getRunId(),
                delivery.getRollingCount(),
                delivery.getCountPath(),
                delivery.getCooldownPath(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getThresholdEventReceivedAt(),
                delivery.getDetectedAt(),
                delivery.getQueuedAt(),
                delivery.getFirstAttemptAt(),
                delivery.getSentAt(),
                delivery.getLastError()
        );
    }
}
