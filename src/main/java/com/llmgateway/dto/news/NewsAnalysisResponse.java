package com.llmgateway.dto.news;

import java.util.List;

public class NewsAnalysisResponse {

    private String title;
    private String symbol;
    private List<String> summary;    // 3-5 gạch đầu dòng tóm tắt bài báo
    private String sentiment;        // "BULLISH", "BEARISH", "NEUTRAL"
    private Integer confidence;      // Độ tin cậy từ 0 - 100%
    private String reason;           // 1-2 câu giải thích nguyên nhân
    private boolean fromCache;       // true nếu đọc từ CSDL Oracle, false nếu gọi Gemini AI mới

    public NewsAnalysisResponse() {
    }

    public NewsAnalysisResponse(String title, String symbol, List<String> summary, String sentiment, Integer confidence, String reason, boolean fromCache) {
        this.title = title;
        this.symbol = symbol;
        this.summary = summary;
        this.sentiment = sentiment;
        this.confidence = confidence;
        this.reason = reason;
        this.fromCache = fromCache;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public List<String> getSummary() {
        return summary;
    }

    public void setSummary(List<String> summary) {
        this.summary = summary;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }
}
