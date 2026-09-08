package com.llmgateway.repository;

import com.llmgateway.entity.Holding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByWalletId(Long walletId);

    Optional<Holding> findByWalletIdAndSymbol(Long walletId, String symbol);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Holding h WHERE h.walletId = :walletId AND h.symbol = :symbol")
    Optional<Holding> findByWalletIdAndSymbolForUpdate(@Param("walletId") Long walletId, @Param("symbol") String symbol);

    void deleteByWalletId(Long walletId);
}
