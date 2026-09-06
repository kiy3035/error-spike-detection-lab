package dev.errordetection.error.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.errordetection.error.api.CreateErrorRequest;
import dev.errordetection.error.domain.ErrorEvent;
import dev.errordetection.error.domain.ErrorEventRepository;
import dev.errordetection.error.domain.ErrorSeverity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ErrorPersistenceServiceTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-06T00:00:00Z");

    @Mock
    private ErrorEventRepository repository;

    private final Clock clock = Clock.fixed(RECEIVED_AT, ZoneOffset.UTC);

    /**
     * 서버 시계와 기본 runId가 저장 엔티티에 적용되는지 확인합니다.
     */
    @Test
    void savesServerReceivedAtAndDefaultRunId() {
        when(repository.saveAndFlush(any(ErrorEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ErrorPersistenceService service = new ErrorPersistenceService(repository, clock);
        CreateErrorRequest request = new CreateErrorRequest(
                "order-api",
                "SYNTHETIC_TIMEOUT",
                ErrorSeverity.ERROR,
                "synthetic message",
                Instant.parse("2026-09-05T23:59:00Z"),
                "trace-1",
                null
        );

        ErrorEvent saved = service.save(request);

        assertThat(saved.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(saved.getRunId()).isEqualTo("manual");
        assertThat(saved.getOccurredAt()).isEqualTo(request.occurredAt());
    }
}
