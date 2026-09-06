package dev.errordetection.infrastructure.notification;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notification")
public record NotificationEndpointProperties(
        URI baseUrl,
        String healthPath,
        String alertPath,
        Duration connectTimeout,
        Duration readTimeout
) {
}
