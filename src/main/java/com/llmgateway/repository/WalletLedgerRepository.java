package com.llmgateway.repository;

import com.llmgateway.entity.WalletLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {

    boolean existsByPaymentOrderId(Long paymentOrderId);

    List<WalletLedger> findAllByWalletIdOrderByCreatedAtDesc(Long walletId);
}