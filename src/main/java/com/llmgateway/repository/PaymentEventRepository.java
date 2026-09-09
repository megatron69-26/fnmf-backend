package com.llmgateway.repository;

import com.llmgateway.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

    Optional<PaymentEvent> findByProviderAndProviderEventId(String provider, String providerEventId);
}