package dev.errordetection.error.api;

import java.time.Instant;

public record CreateErrorResponse(
        long errorId,
        Instant receivedAt
) {
}
