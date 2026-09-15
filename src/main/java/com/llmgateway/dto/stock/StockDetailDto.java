package com.llmgateway.dto.stock;

import java.math.BigDecimal;

/**
 * Thông tin chi tiết một cổ phiếu gồm giá thực tế, thời điểm giá, trạng thái stale,
 * khuyến nghị 2 nhãn và bài báo phân tích thực tế mới nhất.
 */
public record StockDetailDto(
        String symbol,
        String name,
        BigDecimal currentPrice,
        BigDecimal change24h,
        String priceAsOf,
        Boolean stale,
        String recommendation,
        String latestReportTitle,
        String latestReportUrl
) {
}
