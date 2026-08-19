package com.llmgateway.dto.trade;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class OrderRequest {

    @NotBlank(message = "Mã tài sản không được để trống")
    private String symbol; // "BTCUSDT", "XAUUSD", "USOIL", "ETHUSDT"

    @NotBlank(message = "Loại lệnh không được để trống")
    @Pattern(regexp = "^(BUY|SELL)$", message = "Loại lệnh phải là BUY hoặc SELL")
    private String type; // "BUY" hoặc "SELL"

    @NotNull(message = "Khối lượng giao dịch không được để trống")
    @DecimalMin(value = "0.000001", message = "Khối lượng giao dịch phải lớn hơn 0")
    private BigDecimal quantity;

    public OrderRequest() {
    }

    public OrderRequest(String symbol, String type, BigDecimal quantity) {
        this.symbol = symbol;
        this.type = type;
        this.quantity = quantity;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
}
