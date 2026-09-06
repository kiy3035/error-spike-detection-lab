package dev.errordetection.error.api;

import dev.errordetection.error.domain.ErrorSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateErrorRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        String source,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Z0-9_]+")
        String errorCode,

        @NotNull
        ErrorSeverity severity,

        @NotBlank
        @Size(max = 500)
        String message,

        @NotNull
        Instant occurredAt,

        @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        String traceId,

        @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9._-]+")
        String runId
) {
}
