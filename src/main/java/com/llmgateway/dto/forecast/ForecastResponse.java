package com.llmgateway.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ForecastResponse {

    private String symbol;
    private String assetName;
    private BigDecimal currentPrice;
    private String trendPrediction;     // "BULLISH_UPTREND", "BEARISH_DOWNTREND", "SIDEWAYS"
    private String timeframe;           // "24H_7D"
    private BigDecimal supportLevel;    // Ngưỡng hỗ trợ
    private BigDecimal resistanceLevel; // Ngưỡng kháng cự
    private String recommendation;      // "STRONG_BUY", "BUY", "HOLD", "SELL", "STRONG_SELL"
    private Integer confidenceScore;    // % Độ tin cậy (0 - 100)
    private List<String> keyDrivers;    // 3 gạch đầu dòng giải thích nguyên nhân
    private String technicalOutlook;    // Nhận định kỹ thuật (Nến, đường giá)
    private String fundamentalOutlook;  // Nhận định tin tức vĩ mô (FED, lạm phát, tin tức)
    private boolean fromCache;          // true nếu lấy từ Oracle DB cache
    private LocalDateTime createdAt;

    public ForecastResponse() {
    }

    public ForecastResponse(String symbol, String assetName, BigDecimal currentPrice, String trendPrediction, String timeframe, BigDecimal supportLevel, BigDecimal resistanceLevel, String recommendation, Integer confidenceScore, List<String> keyDrivers, String technicalOutlook, String fundamentalOutlook, boolean fromCache, LocalDateTime createdAt) {
        this.symbol = symbol;
        this.assetName = assetName;
        this.currentPrice = currentPrice;
        this.trendPrediction = trendPrediction;
        this.timeframe = timeframe;
        this.supportLevel = supportLevel;
        this.resistanceLevel = resistanceLevel;
        this.recommendation = recommendation;
        this.confidenceScore = confidenceScore;
        this.keyDrivers = keyDrivers;
        this.technicalOutlook = technicalOutlook;
        this.fundamentalOutlook = fundamentalOutlook;
        this.fromCache = fromCache;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
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

    public Integer getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Integer confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public List<String> getKeyDrivers() {
        return keyDrivers;
    }

    public void setKeyDrivers(List<String> keyDrivers) {
        this.keyDrivers = keyDrivers;
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

    public boolean isFromCache() {
        return fromCache;
    }

    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
