package dev.errordetection.alert;

import java.util.List;

public record AlertHistoryResponse(
        List<AlertHistoryItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
