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

    // [KIáº¾N TRÃšC] Optimistic Locking: Chá»‘ng Race Condition khi giao dá»‹ch Ä‘á»“ng thá» i
    @jakarta.persistence.Version
    private Long version;

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

    // [OOP] Domain-Driven Design: Remove public setBalanceUsd
    // Instead, use business methods to encapsulate logic and ensure safety.
    public void deductFunds(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Sá»‘ tiá» n trá»« pháº£i lá»›n hÆ¡n 0");
        }
        if (this.balanceUsd.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Sá»‘ dÆ° khÃ´ng Ä‘á»§ Ä‘á»ƒ thá»±c hiá»‡n giao dá»‹ch!");
        }
        this.balanceUsd = this.balanceUsd.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void addFunds(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Sá»‘ tiá» n cá»™ng pháº£i lá»›n hÆ¡n 0");
        }
        this.balanceUsd = this.balanceUsd.add(amount);
        this.updatedAt = LocalDateTime.now();
    }

    // [OOP] Admin Override: Only to be used by AdminController for resetting accounts
    public void forceSetBalance(BigDecimal balance) {
        if (balance == null || balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Sá»‘ dÆ° pháº£i >= 0");
        }
        this.balanceUsd = balance;
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
