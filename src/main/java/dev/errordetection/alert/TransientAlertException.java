package dev.errordetection.alert;

public class TransientAlertException extends RuntimeException {

    /**
     * 재시도할 수 있는 알림 전송 실패를 원인과 함께 만듭니다.
     *
     * @param category 외부에 노출해도 되는 오류 분류
     * @param cause 원본 예외
     */
    public TransientAlertException(String category, Throwable cause) {
        super(category, cause);
    }
}
