package com.llmgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "MARKET_FORECASTS")
public class MarketForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "current_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "trend_prediction", nullable = false, length = 50)
    private String trendPrediction; // e.g. BULLISH_UPTREND, BEARISH_DOWNTREND, SIDEWAYS_CONSOLIDATION

    @Column(length = 50)
    private String timeframe; // e.g. 24H_7D

    @Column(name = "support_level", precision = 18, scale = 4)
    private BigDecimal supportLevel;

    @Column(name = "resistance_level", precision = 18, scale = 4)
    private BigDecimal resistanceLevel;

    @Column(nullable = false, length = 50)
    private String recommendation; // STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL

    @Column(name = "confidence_score", precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(name = "analysis_summary", length = 4000)
    private String analysisSummary; // JSON or text of key driving points

    @Column(name = "technical_outlook", length = 2000)
    private String technicalOutlook;

    @Column(name = "fundamental_outlook", length = 2000)
    private String fundamentalOutlook;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public MarketForecast() {
    }

    public MarketForecast(String symbol, BigDecimal currentPrice, String trendPrediction, String timeframe, BigDecimal supportLevel, BigDecimal resistanceLevel, String recommendation, BigDecimal confidenceScore, String analysisSummary, String technicalOutlook, String fundamentalOutlook) {
        this.symbol = symbol;
        this.currentPrice = currentPrice;
        this.trendPrediction = trendPrediction;
        this.timeframe = timeframe;
        this.supportLevel = supportLevel;
        this.resistanceLevel = resistanceLevel;
        this.recommendation = recommendation;
        this.confidenceScore = confidenceScore;
        this.analysisSummary = analysisSummary;
        this.technicalOutlook = technicalOutlook;
        this.fundamentalOutlook = fundamentalOutlook;
        this.createdAt = LocalDateTime.now();
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

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public String getTrendPrediction() {
        return trendPrediction;
    }

    public void setTrendPrediction(String trendPrediction) {
        this.trendPrediction = trendPrediction;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public BigDecimal getSupportLevel() {
        return supportLevel;
    }

    public void setSupportLevel(BigDecimal supportLevel) {
        this.supportLevel = supportLevel;
    }

    public BigDecimal getResistanceLevel() {
        return resistanceLevel;
    }

    public void setResistanceLevel(BigDecimal resistanceLevel) {
        this.resistanceLevel = resistanceLevel;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(BigDecimal confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getAnalysisSummary() {
        return analysisSummary;
    }

    public void setAnalysisSummary(String analysisSummary) {
        this.analysisSummary = analysisSummary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getTechnicalOutlook() {
        return technicalOutlook;
    }

    public void setTechnicalOutlook(String technicalOutlook) {
        this.technicalOutlook = technicalOutlook;
    }

    public String getFundamentalOutlook() {
        return fundamentalOutlook;
    }

    public void setFundamentalOutlook(String fundamentalOutlook) {
        this.fundamentalOutlook = fundamentalOutlook;
    }
}
