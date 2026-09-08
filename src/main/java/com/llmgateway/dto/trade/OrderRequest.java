package com.llmgateway.dto.trade;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class OrderRequest {

    @NotBlank(message = "Mã tài sản không được để trống")
    private String symbol; // "BTCUSDT", "ETHUSDT", "XAUUSD"

    @NotBlank(message = "Loại lệnh không được để trống")
    @Pattern(regexp = "^(BUY|SELL)$", message = "Loại lệnh phải là BUY hoặc SELL")
    private String type; // "BUY" hoặc "SELL"

    @NotNull(message = "Khối lượng giao dịch không được để trống")
    @DecimalMin(value = "0.000001", message = "Khối lượng giao dịch phải lớn hơn 0")
    @Digits(integer = 12, fraction = 6, message = "Khối lượng giao dịch tối đa 6 chữ số thập phân")
    private BigDecimal quantity;

    private String clientOrderId; // Khóa chống trùng lặp (Idempotency Key - UUID từ Android)

    public OrderRequest() {
    }

    public OrderRequest(String symbol, String type, BigDecimal quantity) {
        this(symbol, type, quantity, null);
    }

    public OrderRequest(String symbol, String type, BigDecimal quantity, String clientOrderId) {
        this.symbol = symbol;
        this.type = type;
        this.quantity = quantity;
        this.clientOrderId = clientOrderId;
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

    public String getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }
}
