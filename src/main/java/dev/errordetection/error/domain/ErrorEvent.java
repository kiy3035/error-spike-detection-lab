package dev.errordetection.error.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "error_event")
public class ErrorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "error_code", nullable = false, length = 64)
    private String errorCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ErrorSeverity severity;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "run_id", nullable = false, length = 100)
    private String runId;

    /**
     * JPA가 엔티티를 생성할 때 사용하는 기본 생성자입니다.
     */
    protected ErrorEvent() {
    }

    /**
     * 검증된 합성 에러 데이터를 영속 엔티티로 만듭니다.
     *
     * @param source 에러 발생원
     * @param errorCode 합성 에러 코드
     * @param severity 심각도
     * @param message 합성 메시지
     * @param occurredAt 클라이언트가 전달한 발생 시각
     * @param receivedAt 서버 수신 시각
     * @param traceId 합성 추적 식별자
     * @param runId 실험 실행 식별자
     */
    public ErrorEvent(
            String source,
            String errorCode,
            ErrorSeverity severity,
            String message,
            Instant occurredAt,
            Instant receivedAt,
            String traceId,
            String runId
    ) {
        this.source = source;
        this.errorCode = errorCode;
        this.severity = severity;
        this.message = message;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.traceId = traceId;
        this.runId = runId;
    }

    /** @return 에러 식별자 */
    public Long getId() {
        return id;
    }

    /** @return 에러 발생원 */
    public String getSource() {
        return source;
    }

    /** @return 합성 에러 코드 */
    public String getErrorCode() {
        return errorCode;
    }

    /** @return 심각도 */
    public ErrorSeverity getSeverity() {
        return severity;
    }

    /** @return 합성 메시지 */
    public String getMessage() {
        return message;
    }

    /** @return 클라이언트 발생 시각 */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return 서버 수신 시각 */
    public Instant getReceivedAt() {
        return receivedAt;
    }

    /** @return 합성 추적 식별자 */
    public String getTraceId() {
        return traceId;
    }

    /** @return 실험 실행 식별자 */
    public String getRunId() {
        return runId;
    }
}
