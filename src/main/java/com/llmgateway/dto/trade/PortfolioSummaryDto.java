package com.llmgateway.dto.trade;

import java.math.BigDecimal;
import java.util.List;

public class PortfolioSummaryDto {

    private BigDecimal cashBalanceUsd;     // Tiền mặt khả dụng trong ví
    private BigDecimal initialBalanceUsd;  // Vốn ban đầu ($10,000.00)
    private BigDecimal totalHoldingsValue; // Tổng giá trị tài sản đang nắm giữ (USD)
    private BigDecimal totalNetWorth;      // Tổng tài sản ròng = Tiền mặt + Tài sản
    private BigDecimal totalPnL;           // Tổng lời/lỗ = NetWorth - InitialBalance
    private BigDecimal totalPnLPercent;    // % Lời/lỗ tổng tài khoản
    private List<HoldingDto> holdings;     // Danh sách các tài sản chi tiết

    public PortfolioSummaryDto() {
    }

    public PortfolioSummaryDto(BigDecimal cashBalanceUsd, BigDecimal initialBalanceUsd, BigDecimal totalHoldingsValue, BigDecimal totalNetWorth, BigDecimal totalPnL, BigDecimal totalPnLPercent, List<HoldingDto> holdings) {
        this.cashBalanceUsd = cashBalanceUsd;
        this.initialBalanceUsd = initialBalanceUsd;
        this.totalHoldingsValue = totalHoldingsValue;
        this.totalNetWorth = totalNetWorth;
        this.totalPnL = totalPnL;
        this.totalPnLPercent = totalPnLPercent;
        this.holdings = holdings;
    }

    public BigDecimal getCashBalanceUsd() {
        return cashBalanceUsd;
    }

    public void setCashBalanceUsd(BigDecimal cashBalanceUsd) {
        this.cashBalanceUsd = cashBalanceUsd;
    }

    public BigDecimal getInitialBalanceUsd() {
        return initialBalanceUsd;
    }

    public void setInitialBalanceUsd(BigDecimal initialBalanceUsd) {
        this.initialBalanceUsd = initialBalanceUsd;
    }

    public BigDecimal getTotalHoldingsValue() {
        return totalHoldingsValue;
    }

    public void setTotalHoldingsValue(BigDecimal totalHoldingsValue) {
        this.totalHoldingsValue = totalHoldingsValue;
    }

    public BigDecimal getTotalNetWorth() {
        return totalNetWorth;
    }

    public void setTotalNetWorth(BigDecimal totalNetWorth) {
        this.totalNetWorth = totalNetWorth;
    }

    public BigDecimal getTotalPnL() {
        return totalPnL;
    }

    public void setTotalPnL(BigDecimal totalPnL) {
        this.totalPnL = totalPnL;
    }

    public BigDecimal getTotalPnLPercent() {
        return totalPnLPercent;
    }

    public void setTotalPnLPercent(BigDecimal totalPnLPercent) {
        this.totalPnLPercent = totalPnLPercent;
    }

    public List<HoldingDto> getHoldings() {
        return holdings;
    }

    public void setHoldings(List<HoldingDto> holdings) {
        this.holdings = holdings;
    }
}
