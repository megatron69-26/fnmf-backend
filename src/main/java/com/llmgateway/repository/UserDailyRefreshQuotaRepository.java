package com.llmgateway.repository;

import com.llmgateway.entity.UserDailyRefreshQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface UserDailyRefreshQuotaRepository extends JpaRepository<UserDailyRefreshQuota, Long> {

    Optional<UserDailyRefreshQuota> findByUserIdAndQuotaDate(Long userId, LocalDate quotaDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM UserDailyRefreshQuota q WHERE q.userId = :userId AND q.quotaDate = :quotaDate")
    Optional<UserDailyRefreshQuota> findByUserIdAndQuotaDateForUpdate(
            @Param("userId") Long userId,
            @Param("quotaDate") LocalDate quotaDate
    );
}
