package dev.errordetection.error.application;

import dev.errordetection.counter.CounterResult;
import dev.errordetection.counter.RollingErrorCounter;
import dev.errordetection.error.api.CreateErrorRequest;
import dev.errordetection.error.domain.ErrorEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class ErrorIngestionFacade {

    private final ErrorPersistenceService persistenceService;
    private final RollingErrorCounter rollingErrorCounter;
    private final MeterRegistry meterRegistry;

    /**
     * DB commit 뒤 감지를 수행할 별도 Spring Bean들을 주입합니다.
     *
     * @param persistenceService 트랜잭션 저장 서비스
     * @param rollingErrorCounter rolling count 서비스
     * @param meterRegistry 수신 건수 metric 레지스트리
     */
    public ErrorIngestionFacade(
            ErrorPersistenceService persistenceService,
            RollingErrorCounter rollingErrorCounter,
            MeterRegistry meterRegistry
    ) {
        this.persistenceService = persistenceService;
        this.rollingErrorCounter = rollingErrorCounter;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 에러 저장 트랜잭션이 정상 반환된 뒤 같은 receivedAt으로 rolling count를 계산합니다.
     *
     * @param request 검증된 에러 요청
     * @return 저장 엔티티와 counter 결과
     */
    public IngestionResult ingest(CreateErrorRequest request) {
        ErrorEvent saved = persistenceService.save(request);
        meterRegistry.counter("error.events.received").increment();
        CounterResult counter = rollingErrorCounter.incrementAndCount(saved.getSource(), saved.getReceivedAt());
        return new IngestionResult(saved, counter);
    }

    public record IngestionResult(ErrorEvent event, CounterResult counter) {
    }
}
