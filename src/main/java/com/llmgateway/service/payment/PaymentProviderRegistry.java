package com.llmgateway.service.payment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PaymentProviderRegistry {

    private final Map<String, PaymentProvider> providers = new ConcurrentHashMap<>();
    private final PaymentProvider defaultProvider;

    public PaymentProviderRegistry(List<PaymentProvider> providerList,
                                   InternalSandboxPaymentProvider defaultProvider) {
        this.defaultProvider = defaultProvider;
        for (PaymentProvider p : providerList) {
            providers.put(p.getProviderName().toUpperCase(), p);
        }
    }

    public PaymentProvider getProvider(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return defaultProvider;
        }
        PaymentProvider found = providers.get(providerName.trim().toUpperCase());
        return (found != null) ? found : defaultProvider;
    }

    public PaymentProvider getDefaultProvider() {
        return defaultProvider;
    }
}