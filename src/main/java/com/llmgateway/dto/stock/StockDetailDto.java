package com.llmgateway.dto.stock;

import java.math.BigDecimal;

/**
 * Thông tin chi tiết một cổ phiếu gồm giá thực tế, thời điểm giá, trạng thái stale,
 * khuyến nghị 2 nhãn, bài báo thực tế và metadata nhà cung cấp (marketDataProvider, aiShard).
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
        String latestReportUrl,
        String marketDataProvider,
        String aiShard
) {
    public StockDetailDto(
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
        this(symbol, name, currentPrice, change24h, priceAsOf, stale, recommendation, latestReportTitle, latestReportUrl, null, null);
    }
}
