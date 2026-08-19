package com.llmgateway.dto.trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class OrderResponse {

    private Long transactionId;
    private String symbol;
    private String type;         // "BUY" / "SELL"
    private BigDecimal price;    // Giá khớp lệnh
    private BigDecimal quantity; // Khối lượng
    private BigDecimal totalAmount; // Tổng giá trị lệnh (USD)
    private BigDecimal remainingBalance; // Số dư ví còn lại (USD)
    private LocalDateTime executedAt;
    private String message;

    public OrderResponse() {
    }

    public OrderResponse(Long transactionId, String symbol, String type, BigDecimal price, BigDecimal quantity, BigDecimal totalAmount, BigDecimal remainingBalance, LocalDateTime executedAt, String message) {
        this.transactionId = transactionId;
        this.symbol = symbol;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.remainingBalance = remainingBalance;
        this.executedAt = executedAt;
        this.message = message;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getRemainingBalance() {
        return remainingBalance;
    }

    public void setRemainingBalance(BigDecimal remainingBalance) {
        this.remainingBalance = remainingBalance;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
