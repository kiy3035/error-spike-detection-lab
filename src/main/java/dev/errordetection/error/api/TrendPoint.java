package dev.errordetection.error.api;

import java.time.Instant;

public record TrendPoint(
        Instant bucketStart,
        long count
) {
}
