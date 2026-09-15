package com.llmgateway.dto.watchlist;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WatchlistItemDto {

    private Long id;
    private String symbol;
    private String name;
    private String category;
    private BigDecimal currentPrice;
    private BigDecimal change24h;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private String priceAsOf;
    private Boolean stale = false;
    private String recommendation;
    private String latestReportTitle;
    private String latestReportUrl;

    public WatchlistItemDto() {
    }

    public WatchlistItemDto(Long id, String symbol, String name, String category, BigDecimal currentPrice, BigDecimal change24h, Integer displayOrder, LocalDateTime createdAt) {
        this(id, symbol, name, category, currentPrice, change24h, displayOrder, createdAt, null, false, null, null, null);
    }

    public WatchlistItemDto(Long id, String symbol, String name, String category, BigDecimal currentPrice, BigDecimal change24h, Integer displayOrder, LocalDateTime createdAt,
                            String priceAsOf, Boolean stale, String recommendation, String latestReportTitle, String latestReportUrl) {
        this.id = id;
        this.symbol = symbol;
        this.name = name;
        this.category = category;
        this.currentPrice = currentPrice;
        this.change24h = change24h;
        this.displayOrder = displayOrder;
        this.createdAt = createdAt;
        this.priceAsOf = priceAsOf;
        this.stale = stale != null ? stale : false;
        this.recommendation = recommendation;
        this.latestReportTitle = latestReportTitle;
        this.latestReportUrl = latestReportUrl;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public BigDecimal getChange24h() {
        return change24h;
    }

    public void setChange24h(BigDecimal change24h) {
        this.change24h = change24h;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getPriceAsOf() {
        return priceAsOf;
    }

    public void setPriceAsOf(String priceAsOf) {
        this.priceAsOf = priceAsOf;
    }

    public Boolean getStale() {
        return stale;
    }

    public void setStale(Boolean stale) {
        this.stale = stale;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public String getLatestReportTitle() {
        return latestReportTitle;
    }

    public void setLatestReportTitle(String latestReportTitle) {
        this.latestReportTitle = latestReportTitle;
    }

    public String getLatestReportUrl() {
        return latestReportUrl;
    }

    public void setLatestReportUrl(String latestReportUrl) {
        this.latestReportUrl = latestReportUrl;
    }
}
