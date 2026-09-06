package dev.errordetection.error.application;

import dev.errordetection.error.api.CreateErrorRequest;
import dev.errordetection.error.domain.ErrorEvent;
import dev.errordetection.error.domain.ErrorEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ErrorPersistenceService {

    private static final String DEFAULT_RUN_ID = "manual";

    private final ErrorEventRepository repository;
    private final Clock clock;

    /**
     * 에러 저장에 필요한 저장소와 서버 시계를 주입합니다.
     *
     * @param repository 에러 저장소
     * @param clock 서버 수신 시각 기준 시계
     */
    public ErrorPersistenceService(ErrorEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 합성 에러를 서버 수신 시각과 함께 하나의 트랜잭션으로 저장합니다.
     *
     * @param request 검증된 에러 요청
     * @return 저장되고 flush된 에러 엔티티
     */
    @Transactional
    public ErrorEvent save(CreateErrorRequest request) {
        Instant receivedAt = clock.instant();
        ErrorEvent event = new ErrorEvent(
                request.source(),
                request.errorCode(),
                request.severity(),
                request.message(),
                request.occurredAt(),
                receivedAt,
                request.traceId(),
                normalizeRunId(request.runId())
        );
        return repository.saveAndFlush(event);
    }

    /**
     * 비어 있는 실행 식별자를 안전한 로컬 기본값으로 치환합니다.
     *
     * @param runId 요청 실행 식별자
     * @return 저장에 사용할 실행 식별자
     */
    private String normalizeRunId(String runId) {
        return runId == null || runId.isBlank() ? DEFAULT_RUN_ID : runId;
    }
}
