package com.llmgateway.service.payment;

import com.llmgateway.entity.PaymentOrder;

public interface PaymentProvider {
    String getProviderName();
    String buildCheckoutUrl(PaymentOrder order, String baseUrl);
    boolean isSandbox();
}