package com.llmgateway.service.payment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PaymentProviderRegistry {

    public static final String PROVIDER_INTERNAL = "SANDBOX_INTERNAL";
    public static final String PROVIDER_VNPAY = "VNPAY_SANDBOX";

    private final Map<String, PaymentProvider> providers = new ConcurrentHashMap<>();
    private final PaymentProvider defaultProvider;
    private final String configuredProviderName;

    public PaymentProviderRegistry(List<PaymentProvider> providerList,
                                   InternalSandboxPaymentProvider defaultProvider,
                                   @org.springframework.beans.factory.annotation.Value("${payment.provider:SANDBOX_INTERNAL}") String configuredProviderName) {
        this.defaultProvider = defaultProvider;
        this.configuredProviderName = configuredProviderName;
        for (PaymentProvider p : providerList) {
            providers.put(p.getProviderName().toUpperCase(), p);
        }
    }

    public PaymentProvider getProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("Tên cổng thanh toán không được để trống");
        }
        PaymentProvider found = providers.get(providerName.trim().toUpperCase());
        if (found == null) {
            throw new IllegalArgumentException("Không tìm thấy cổng thanh toán được hỗ trợ: " + providerName);
        }
        return found;
    }

    public PaymentProvider getConfiguredProvider() {
        if (configuredProviderName == null || configuredProviderName.isBlank()) {
            return defaultProvider;
        }
        return getProvider(configuredProviderName);
    }

    public PaymentProvider getDefaultProvider() {
        return defaultProvider;
    }

    public void registerProvider(PaymentProvider provider) {
        if (provider != null && provider.getProviderName() != null) {
            providers.put(provider.getProviderName().trim().toUpperCase(), provider);
        }
    }
}