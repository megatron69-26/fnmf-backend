package com.llmgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity ánh xạ với bảng WALLETS trong Oracle Database.
 * Quản lý ví vốn ảo cho tính năng Paper Trading.
 */
@Entity
@Table(name = "WALLETS")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "USER_ID", nullable = false, unique = true)
    private Long userId;

    @Column(name = "BALANCE_USD", nullable = false, precision = 18, scale = 4)
    private BigDecimal balanceUsd = new BigDecimal("10000.0000");

    @Column(name = "INITIAL_BALANCE", nullable = false, precision = 18, scale = 4)
    private BigDecimal initialBalance = new BigDecimal("10000.0000");

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // --- Constructors ---
    public Wallet() {
    }

    public Wallet(Long userId) {
        this.userId = userId;
        this.balanceUsd = new BigDecimal("10000.0000");
        this.initialBalance = new BigDecimal("10000.0000");
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Wallet(Long userId, BigDecimal initialBalance) {
        this.userId = userId;
        this.balanceUsd = initialBalance;
        this.initialBalance = initialBalance;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getBalanceUsd() {
        return balanceUsd;
    }

    public void setBalanceUsd(BigDecimal balanceUsd) {
        this.balanceUsd = balanceUsd;
        this.updatedAt = LocalDateTime.now();
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
