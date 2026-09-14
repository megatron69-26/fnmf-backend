package com.llmgateway.dto.forecast;

import jakarta.validation.constraints.NotBlank;

public class ForecastRequest {

    @NotBlank(message = "Mã tài sản không được để trống (ví dụ: BTCUSDT, ETHUSDT, XAUUSD, USOIL)")
    private String symbol;

    private String timeframe = "24H_7D"; // Mặc định khung thời gian 24 giờ đến 7 ngày
    private String clientRequestId;

    public ForecastRequest() {
    }

    public ForecastRequest(String symbol, String timeframe) {
        this(symbol, timeframe, null);
    }

    public ForecastRequest(String symbol, String timeframe, String clientRequestId) {
        this.symbol = symbol;
        this.timeframe = timeframe != null ? timeframe : "24H_7D";
        this.clientRequestId = clientRequestId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }
}
