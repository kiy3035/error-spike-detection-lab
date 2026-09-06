package dev.errordetection.alert;

import dev.errordetection.counter.CounterPath;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_delivery")
public class AlertDelivery {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "run_id", nullable = false, length = 100)
    private String runId;

    @Column(name = "rolling_count", nullable = false)
    private long rollingCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "count_path", nullable = false, length = 16)
    private CounterPath countPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "cooldown_path", nullable = false, length = 16)
    private CooldownPath cooldownPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AlertStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "threshold_event_received_at", nullable = false)
    private Instant thresholdEventReceivedAt;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "first_attempt_at")
    private Instant firstAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * JPA가 알림 이력을 생성할 때 사용하는 기본 생성자입니다.
     */
    protected AlertDelivery() {
    }

    /**
     * cooldown을 선점한 logical alert의 PENDING 이력을 만듭니다.
     *
     * @param id idempotency key로 사용할 식별자
     * @param source 에러 발생원
     * @param runId 실험 실행 식별자
     * @param rollingCount 감지 시점 건수
     * @param countPath count 실행 경로
     * @param cooldownPath cooldown 선점 경로
     * @param thresholdEventReceivedAt 임계값 이벤트 수신 시각
     * @param detectedAt 감지 시각
     * @param queuedAt 비동기 등록 시각
     */
    public AlertDelivery(
            UUID id,
            String source,
            String runId,
            long rollingCount,
            CounterPath countPath,
            CooldownPath cooldownPath,
            Instant thresholdEventReceivedAt,
            Instant detectedAt,
            Instant queuedAt
    ) {
        this.id = id;
        this.source = source;
        this.runId = runId;
        this.rollingCount = rollingCount;
        this.countPath = countPath;
        this.cooldownPath = cooldownPath;
        this.status = AlertStatus.PENDING;
        this.attemptCount = 0;
        this.thresholdEventReceivedAt = thresholdEventReceivedAt;
        this.detectedAt = detectedAt;
        this.queuedAt = queuedAt;
    }

    /**
     * HTTP 전송 시도 횟수와 최초 시도 시각을 기록합니다.
     *
     * @param attemptedAt 전송 시도 시각
     */
    public void recordAttempt(Instant attemptedAt) {
        attemptCount++;
        if (firstAttemptAt == null) {
            firstAttemptAt = attemptedAt;
        }
    }

    /**
     * endpoint 성공 응답 뒤 SENT 상태와 성공 시각을 기록합니다.
     *
     * @param sentAt 성공 시각
     */
    public void markSent(Instant sentAt) {
        status = AlertStatus.SENT;
        this.sentAt = sentAt;
        lastError = null;
    }

    /**
     * 재시도 불가 또는 재시도 소진 오류를 정제해 FAILED로 기록합니다.
     *
     * @param sanitizedError 정제된 오류 분류
     */
    public void markFailed(String sanitizedError) {
        status = AlertStatus.FAILED;
        lastError = sanitizedError;
    }

    /** @return logical alert 식별자 */
    public UUID getId() { return id; }

    /** @return 에러 발생원 */
    public String getSource() { return source; }

    /** @return 실행 식별자 */
    public String getRunId() { return runId; }

    /** @return 감지 건수 */
    public long getRollingCount() { return rollingCount; }

    /** @return count 경로 */
    public CounterPath getCountPath() { return countPath; }

    /** @return cooldown 경로 */
    public CooldownPath getCooldownPath() { return cooldownPath; }

    /** @return 전송 상태 */
    public AlertStatus getStatus() { return status; }

    /** @return 실제 시도 횟수 */
    public int getAttemptCount() { return attemptCount; }

    /** @return 임계값 이벤트 수신 시각 */
    public Instant getThresholdEventReceivedAt() { return thresholdEventReceivedAt; }

    /** @return 감지 시각 */
    public Instant getDetectedAt() { return detectedAt; }

    /** @return 큐 등록 시각 */
    public Instant getQueuedAt() { return queuedAt; }

    /** @return 최초 시도 시각 */
    public Instant getFirstAttemptAt() { return firstAttemptAt; }

    /** @return 성공 시각 */
    public Instant getSentAt() { return sentAt; }

    /** @return 정제된 마지막 오류 */
    public String getLastError() { return lastError; }
}
