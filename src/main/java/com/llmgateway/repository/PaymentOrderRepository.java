package com.llmgateway.repository;

import com.llmgateway.entity.PaymentOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    Optional<PaymentOrder> findByCheckoutToken(String checkoutToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentOrder p WHERE p.checkoutToken = :checkoutToken")
    Optional<PaymentOrder> findByCheckoutTokenForUpdate(@Param("checkoutToken") String checkoutToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentOrder p WHERE p.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") Long id);

    List<PaymentOrder> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PaymentOrder> findByIdAndUserId(Long id, Long userId);
}