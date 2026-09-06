package dev.errordetection.alert;

import java.time.Instant;
import java.util.UUID;

public record AlertPayload(
        UUID alertId,
        String source,
        long rollingCount,
        long windowSeconds,
        Instant detectedAt
) {
}
