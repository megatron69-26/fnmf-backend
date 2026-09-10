package com.llmgateway.service.payment;

import com.llmgateway.entity.PaymentOrder;

public interface PaymentProvider {
    String getProviderName();
    String buildCheckoutUrl(PaymentOrder order, String baseUrl);
    default String buildCheckoutUrl(PaymentOrder order, String baseUrl, String clientIp) {
        return buildCheckoutUrl(order, baseUrl);
    }
    boolean isSandbox();
}