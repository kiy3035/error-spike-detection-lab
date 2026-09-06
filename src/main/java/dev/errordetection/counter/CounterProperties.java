package dev.errordetection.counter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.counter")
public record CounterProperties(
        int windowSeconds,
        int bucketTtlSeconds
) {
}
