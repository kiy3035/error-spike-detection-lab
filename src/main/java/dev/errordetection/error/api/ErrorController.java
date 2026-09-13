package dev.errordetection.error.api;

import dev.errordetection.error.application.ErrorIngestionFacade;
import dev.errordetection.error.application.ErrorQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/errors")
public class ErrorController {

    private final ErrorIngestionFacade ingestionFacade;
    private final ErrorQueryService queryService;

    /**
     * 에러 저장과 조회 서비스를 주입합니다.
     *
     * @param ingestionFacade 저장 후 감지 facade
     * @param queryService 에러 조회 서비스
     */
    public ErrorController(ErrorIngestionFacade ingestionFacade, ErrorQueryService queryService) {
        this.ingestionFacade = ingestionFacade;
        this.queryService = queryService;
    }

    /**
     * 검증된 합성 에러를 PostgreSQL에 영구 저장합니다.
     *
     * @param request 합성 에러 요청
     * @return 생성된 식별자와 서버 수신 시각
     */
    @PostMapping
    public ResponseEntity<CreateErrorResponse> create(@Valid @RequestBody CreateErrorRequest request) {
        var result = ingestionFacade.ingest(request);
        var saved = result.event();
        var counter = result.counter();
        var alert = result.alert();
        var response = new CreateErrorResponse(
                saved.getId(),
                saved.getReceivedAt(),
                counter.path(),
                counter.durationNanos(),
                counter.count(),
                alert.thresholdExceeded(),
                alert.cooldownAcquired(),
                alert.alertQueued()
        );
        return ResponseEntity.created(URI.create("/errors/" + saved.getId())).body(response);
    }

    /**
     * 에러 발생원과 서버 수신 시각 범위로 에러 이력을 페이지 조회합니다.
     *
     * @param source 에러 발생원
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 에러 이력 페이지
     */
    @GetMapping
    public ErrorHistoryResponse history(
            @RequestParam @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String source,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.history(source, from, to, page, size);
    }

    /**
     * 에러 발생원과 서버 수신 시각 범위의 구간별 추이를 조회합니다.
     *
     * @param source 에러 발생원
     * @param from 포함 시작 시각
     * @param to 제외 종료 시각
     * @param bucket 허용된 집계 단위
     * @return 빈 구간을 0으로 채운 추이
     */
    @GetMapping("/trend")
    public ErrorTrendResponse trend(
            @RequestParam @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String source,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam TrendBucket bucket
    ) {
        return queryService.trend(source, from, to, bucket);
    }
}
