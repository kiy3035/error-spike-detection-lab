package dev.errordetection.alert;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertQueryService queryService;

    /**
     * 로컬 검증용 알림 이력 조회 서비스를 주입합니다.
     *
     * @param queryService 알림 이력 조회 서비스
     */
    public AlertController(AlertQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * run, source, 상태와 등록 시각 조건으로 알림 이력을 조회합니다.
     *
     * @param runId 실험 실행 식별자
     * @param source 에러 발생원
     * @param status 알림 상태
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 알림 이력 페이지
     */
    @GetMapping
    public AlertHistoryResponse history(
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String runId,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String source,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.history(runId, source, status, from, to, page, size);
    }
}
