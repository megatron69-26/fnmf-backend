package com.llmgateway.dto.market;

import java.math.BigDecimal;

/**
 * DTO dữ liệu nến Candlestick (OHLCV) cho biểu đồ TradingView / MPAndroidChart.
 */
public class CandleDto {

    private String time;         // Thời gian nến (YYYY-MM-DD HH:mm:ss hoặc timestamp)
    private BigDecimal open;     // Giá mở cửa (Open)
    private BigDecimal high;     // Giá cao nhất (High)
    private BigDecimal low;      // Giá thấp nhất (Low)
    private BigDecimal close;    // Giá đóng cửa (Close)
    private BigDecimal volume;   // Khối lượng giao dịch (Volume)

    public CandleDto() {
    }

    public CandleDto(String time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public void setOpen(BigDecimal open) {
        this.open = open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public void setHigh(BigDecimal high) {
        this.high = high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }

    public BigDecimal getClose() {
        return close;
    }

    public void setClose(BigDecimal close) {
        this.close = close;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }
}
