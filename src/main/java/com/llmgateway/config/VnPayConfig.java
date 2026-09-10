package com.llmgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class VnPayConfig {

    @Value("${payment.vnpay.tmn-code:}")
    private String tmnCode;

    @Value("${payment.vnpay.hash-secret:}")
    private String hashSecret;

    @Value("${payment.vnpay.pay-url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String payUrl;

    @Value("${payment.vnpay.return-url:}")
    private String returnUrl;

    @Value("${payment.vnpay.exchange-rate:25000}")
    private BigDecimal exchangeRate;

    public String getTmnCode() {
        return tmnCode != null ? tmnCode.trim() : "";
    }

    public void setTmnCode(String tmnCode) {
        this.tmnCode = tmnCode;
    }

    public String getHashSecret() {
        return hashSecret != null ? hashSecret.trim() : "";
    }

    public void setHashSecret(String hashSecret) {
        this.hashSecret = hashSecret;
    }

    public String getPayUrl() {
        return payUrl != null ? payUrl.trim() : "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    }

    public void setPayUrl(String payUrl) {
        this.payUrl = payUrl;
    }

    public String getReturnUrl() {
        return returnUrl != null ? returnUrl.trim() : "";
    }

    public void setReturnUrl(String returnUrl) {
        this.returnUrl = returnUrl;
    }

    public BigDecimal getExchangeRate() {
        return (exchangeRate != null && exchangeRate.compareTo(BigDecimal.ZERO) > 0)
                ? exchangeRate
                : new BigDecimal("25000");
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public boolean isConfigured() {
        return !getTmnCode().isEmpty() && !getHashSecret().isEmpty();
    }
}
