package dev.errordetection.error.api;

import dev.errordetection.error.domain.ErrorSeverity;
import java.time.Instant;

public record ErrorHistoryItem(
        long id,
        String source,
        String errorCode,
        ErrorSeverity severity,
        String message,
        Instant occurredAt,
        Instant receivedAt,
        String traceId,
        String runId
) {
}
