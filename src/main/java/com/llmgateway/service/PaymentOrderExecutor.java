package com.llmgateway.service;

import com.llmgateway.entity.PaymentOrder;
import com.llmgateway.entity.PaymentStatus;
import com.llmgateway.entity.PaymentType;
import com.llmgateway.repository.PaymentOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Thực thi việc lưu PaymentOrder trong một transaction riêng biệt (REQUIRES_NEW).
 * Giúp cho outer orchestrator trong PaymentService có thể bắt DataIntegrityViolationException
 * khi gặp raced concurrent requests mà không làm hỏng transaction chính, từ đó phục hồi an toàn (idempotent replay).
 */
@Service
public class PaymentOrderExecutor {

    private final PaymentOrderRepository paymentOrderRepository;

    public PaymentOrderExecutor(PaymentOrderRepository paymentOrderRepository) {
        this.paymentOrderRepository = paymentOrderRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentOrder createAndPersistOrder(Long userId, Long walletId, String clientRequestId,
                                              String providerName, PaymentType type,
                                              BigDecimal amount, String checkoutToken) {
        PaymentOrder order = new PaymentOrder(
                userId,
                walletId,
                clientRequestId,
                providerName,
                type,
                amount,
                PaymentStatus.PENDING,
                checkoutToken
        );
        return paymentOrderRepository.saveAndFlush(order);
    }
}
