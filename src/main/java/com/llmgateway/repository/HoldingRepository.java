package com.llmgateway.repository;

import com.llmgateway.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByWalletId(Long walletId);

    Optional<Holding> findByWalletIdAndSymbol(Long walletId, String symbol);

    void deleteByWalletId(Long walletId);
}
