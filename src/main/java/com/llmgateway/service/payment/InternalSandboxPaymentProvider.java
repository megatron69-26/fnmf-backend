package com.llmgateway.service.payment;

import com.llmgateway.entity.PaymentOrder;
import org.springframework.stereotype.Component;

/**
 * Provider Sandbox noi bo - phuc vu do an mon hoc va khao sat kien truc thanh toan.
 * Tuyet doi khong xu ly tien that, the that hoac thong tin ngan hang that.
 */
@Component
public class InternalSandboxPaymentProvider implements PaymentProvider {

    public static final String PROVIDER_NAME = "SANDBOX_INTERNAL";

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String buildCheckoutUrl(PaymentOrder order, String baseUrl) {
        String base = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/sandbox-bank/checkout/" + order.getCheckoutToken();
    }

    @Override
    public boolean isSandbox() {
        return true;
    }
}