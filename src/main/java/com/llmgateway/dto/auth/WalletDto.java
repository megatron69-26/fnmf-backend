package com.llmgateway.dto.auth;

import java.math.BigDecimal;

public class WalletDto {
    private Long id;
    private Long userId;
    private BigDecimal balanceUsd;
    private BigDecimal initialBalance;

    public WalletDto() {
    }

    public WalletDto(Long id, Long userId, BigDecimal balanceUsd, BigDecimal initialBalance) {
        this.id = id;
        this.userId = userId;
        this.balanceUsd = balanceUsd;
        this.initialBalance = initialBalance;
    }

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
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }
}
