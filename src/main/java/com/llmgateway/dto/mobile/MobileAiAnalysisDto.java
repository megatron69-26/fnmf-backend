package com.llmgateway.dto.mobile;

/**
 * DTO đồng bộ 100% với Room Entity "AI_Analysis" của bạn Mạnh (DungQuenTenAnh-1.0.0).
 *
 * Mapping:
 *   Room (Mạnh)                ←→  Backend (Khôi)
 *   ─────────────────────────────────────────────────
 *   analysisId (int PK auto)   ←   (Server không cần trả, Room tự sinh)
 *   newsId (String FK)         ←   "NEWS_" + id (Long từ Oracle NEWS_AI_CACHE)
 *   summary (String)           ←   summaryPoints (CLOB → String đã join)
 *   sentiment (String)         ←   sentiment (BULLISH/BEARISH/NEUTRAL)
 *   confidenceScore (int)      ←   confidencePct (BigDecimal → int)
 *   reason (String)            ←   reason (CLOB → String)
 */
public class MobileAiAnalysisDto {

    private String newsId;          // FK khớp với MobileNewsDto.newsId
    private String summary;         // Khớp với Room public String summary
    private String sentiment;       // Khớp với Room public String sentiment
    private int confidenceScore;    // Khớp với Room public int confidenceScore
    private String reason;          // Khớp với Room public String reason

    public MobileAiAnalysisDto() {
    }

    public MobileAiAnalysisDto(String newsId, String summary, String sentiment, int confidenceScore, String reason) {
        this.newsId = newsId;
        this.summary = summary;
        this.sentiment = sentiment;
        this.confidenceScore = confidenceScore;
        this.reason = reason;
    }

    public String getNewsId() {
        return newsId;
    }

    public void setNewsId(String newsId) {
        this.newsId = newsId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public int getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(int confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
