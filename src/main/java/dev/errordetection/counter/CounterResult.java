package dev.errordetection.counter;

public record CounterResult(
        long count,
        CounterPath path,
        long durationNanos
) {
}
