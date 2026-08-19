package com.llmgateway.dto.trade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class HoldingDto {

    private Long id;
    private String symbol;
    private String name;
    private BigDecimal quantity;
    private BigDecimal avgBuyPrice;
    private BigDecimal currentPrice;
    private BigDecimal totalCost;      // = quantity * avgBuyPrice
    private BigDecimal currentValue;   // = quantity * currentPrice
    private BigDecimal unrealizedPnL;  // = currentValue - totalCost (Lời/Lỗ USD)
    private BigDecimal pnlPercent;     // Tỷ lệ lời/lỗ (%)
    private LocalDateTime updatedAt;

    public HoldingDto() {
    }

    public HoldingDto(Long id, String symbol, String name, BigDecimal quantity, BigDecimal avgBuyPrice, BigDecimal currentPrice, BigDecimal totalCost, BigDecimal currentValue, BigDecimal unrealizedPnL, BigDecimal pnlPercent, LocalDateTime updatedAt) {
        this.id = id;
        this.symbol = symbol;
        this.name = name;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.currentPrice = currentPrice;
        this.totalCost = totalCost;
        this.currentValue = currentValue;
        this.unrealizedPnL = unrealizedPnL;
        this.pnlPercent = pnlPercent;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAvgBuyPrice() {
        return avgBuyPrice;
    }

    public void setAvgBuyPrice(BigDecimal avgBuyPrice) {
        this.avgBuyPrice = avgBuyPrice;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public BigDecimal getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(BigDecimal currentValue) {
        this.currentValue = currentValue;
    }

    public BigDecimal getUnrealizedPnL() {
        return unrealizedPnL;
    }

    public void setUnrealizedPnL(BigDecimal unrealizedPnL) {
        this.unrealizedPnL = unrealizedPnL;
    }

    public BigDecimal getPnlPercent() {
        return pnlPercent;
    }

    public void setPnlPercent(BigDecimal pnlPercent) {
        this.pnlPercent = pnlPercent;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
