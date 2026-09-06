package dev.errordetection.error.api;

import java.util.List;

public record ErrorHistoryResponse(
        List<ErrorHistoryItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
