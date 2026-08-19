package com.llmgateway.dto.watchlist;

import jakarta.validation.constraints.NotBlank;

public class WatchlistRequest {

    @NotBlank(message = "Mã tài sản (symbol) không được để trống")
    private String symbol;

    private Integer displayOrder;

    public WatchlistRequest() {
    }

    public WatchlistRequest(String symbol, Integer displayOrder) {
        this.symbol = symbol;
        this.displayOrder = displayOrder;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }
}
