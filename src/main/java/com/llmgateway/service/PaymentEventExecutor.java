package com.llmgateway.service;

import com.llmgateway.entity.PaymentEvent;
import com.llmgateway.repository.PaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi nhận PaymentEvent trong transaction độc lập (REQUIRES_NEW)
 * để nếu xảy ra lỗi cơ sở dữ liệu (như duplicate event ID), transaction chính của thanh toán
 * không bị đánh dấu rollback-only.
 */
@Service
public class PaymentEventExecutor {

    private final PaymentEventRepository paymentEventRepository;

    public PaymentEventExecutor(PaymentEventRepository paymentEventRepository) {
        this.paymentEventRepository = paymentEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEvent(PaymentEvent event) {
        paymentEventRepository.saveAndFlush(event);
    }
}