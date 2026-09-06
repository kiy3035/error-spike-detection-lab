package dev.errordetection.error.api;

import java.time.Instant;
import dev.errordetection.counter.CounterPath;

public record CreateErrorResponse(
        long errorId,
        Instant receivedAt,
        CounterPath countPath,
        long rollingCount
) {
}
