package com.llmgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity ghi nhận lịch sử và đảm bảo tính lũy thừa (idempotency) của từng thao tác làm mới thủ công.
 * Mỗi (user_id, client_request_id) là duy nhất.
 */
@Entity
@Table(
        name = "content_refresh_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_content_refresh_event_client_req", columnNames = {"user_id", "client_request_id"})
        }
)
public class ContentRefreshEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    @Column(name = "client_request_id", nullable = false, length = 100)
    private String clientRequestId;

    @Column(name = "module", nullable = false, length = 20)
    private String module;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ContentRefreshEvent() {
    }

    public ContentRefreshEvent(Long userId, LocalDate quotaDate, String clientRequestId, String module, String status) {
        this.userId = userId;
        this.quotaDate = quotaDate;
        this.clientRequestId = clientRequestId;
        this.module = module;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getQuotaDate() {
        return quotaDate;
    }

    public void setQuotaDate(LocalDate quotaDate) {
        this.quotaDate = quotaDate;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
