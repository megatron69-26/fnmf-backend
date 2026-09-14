package com.llmgateway.repository;

import com.llmgateway.entity.ContentRefreshEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContentRefreshEventRepository extends JpaRepository<ContentRefreshEvent, Long> {

    Optional<ContentRefreshEvent> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    List<ContentRefreshEvent> findByUserIdAndQuotaDate(Long userId, LocalDate quotaDate);
}
