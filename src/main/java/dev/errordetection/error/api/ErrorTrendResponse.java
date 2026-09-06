package dev.errordetection.error.api;

import java.time.Instant;
import java.util.List;

public record ErrorTrendResponse(
        String source,
        Instant from,
        Instant to,
        TrendBucket bucket,
        List<TrendPoint> points
) {
}
