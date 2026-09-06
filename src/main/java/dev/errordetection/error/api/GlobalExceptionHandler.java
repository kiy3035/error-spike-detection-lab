package dev.errordetection.error.api;

import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    /**
     * 오류 응답 시각에 사용할 서버 시계를 주입합니다.
     *
     * @param clock 서버 시계
     */
    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * 요청 본문 필드 검증 오류를 민감한 값 없이 400 응답으로 변환합니다.
     *
     * @param exception 검증 예외
     * @return 정제된 오류 응답
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": invalid")
                .distinct()
                .toList();
        return badRequest(details);
    }

    /**
     * 요청 파라미터와 조회 정책 오류를 정제된 400 응답으로 변환합니다.
     *
     * @param exception 파라미터 또는 정책 예외
     * @return 정제된 오류 응답
     */
    @ExceptionHandler({
            ConstraintViolationException.class,
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception) {
        return badRequest(List.of("request: invalid"));
    }

    /**
     * 일관된 형식의 400 오류 응답을 생성합니다.
     *
     * @param details 정제된 필드 목록
     * @return 400 오류 응답
     */
    private ResponseEntity<ApiErrorResponse> badRequest(List<String> details) {
        ApiErrorResponse body = new ApiErrorResponse(
                clock.instant(),
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                details
        );
        return ResponseEntity.badRequest().body(body);
    }
}
