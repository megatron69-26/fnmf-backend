package com.llmgateway.dto.payment;

import java.math.BigDecimal;

public class PaymentOrderResponseDto {

    private Long paymentOrderId;
    private String type;
    private BigDecimal amountUsd;
    private BigDecimal amountVnd;
    private BigDecimal exchangeRateSnapshot;
    private String status;
    private String checkoutUrl;
    private String provider;
    private String createdAt;
    private String completedAt;
    private String message;
    private String failureReason;

    public PaymentOrderResponseDto() {
    }

    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    public void setPaymentOrderId(Long paymentOrderId) {
        this.paymentOrderId = paymentOrderId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public void setAmountUsd(BigDecimal amountUsd) {
        this.amountUsd = amountUsd;
    }

    public BigDecimal getAmountVnd() {
        return amountVnd;
    }

    public void setAmountVnd(BigDecimal amountVnd) {
        this.amountVnd = amountVnd;
    }

    public BigDecimal getExchangeRateSnapshot() {
        return exchangeRateSnapshot;
    }

    public void setExchangeRateSnapshot(BigDecimal exchangeRateSnapshot) {
        this.exchangeRateSnapshot = exchangeRateSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public void setCheckoutUrl(String checkoutUrl) {
        this.checkoutUrl = checkoutUrl;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}