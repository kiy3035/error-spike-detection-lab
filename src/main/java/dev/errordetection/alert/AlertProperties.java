package dev.errordetection.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.alert")
public record AlertProperties(
        long threshold,
        long cooldownSeconds,
        boolean cooldownEnabled,
        Retry retry,
        Executor executor
) {
    public record Retry(int maxAttempts, long initialDelayMs, double multiplier) {
    }

    public record Executor(int coreSize, int maxSize, int queueCapacity) {
    }
}
