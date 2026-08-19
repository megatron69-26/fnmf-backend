package com.llmgateway.dto.news;

import java.util.List;

/**
 * DTO đại diện cho 1 bài báo tài chính thực tế từ Alpha Vantage, được làm giàu bởi Gemini AI.
 */
public class NewsFeedItemDto {

    private String title;
    private String url;
    private String timePublished;
    private String summary;          // Tóm tắt gốc từ báo chí
    private String bannerImage;      // Ảnh bìa bài báo
    private String source;           // Nguồn báo (Investing.com, TradingView, Reuters...)
    private String category;
    private List<String> topics;

    // Dữ liệu phân tích chuyên sâu từ Gemini AI
    private List<String> aiSummary;  // 3 gạch đầu dòng tóm tắt sâu sắc từ Gemini
    private String aiSentiment;      // "BULLISH", "BEARISH", "NEUTRAL"
    private Integer aiConfidence;    // % Độ tin cậy từ 0 - 100
    private String aiReason;         // Lý do giải thích tác động thị trường từ Gemini
    private boolean fromCache;       // true nếu đọc từ CSDL Oracle

    public NewsFeedItemDto() {
    }

    public NewsFeedItemDto(String title, String url, String timePublished, String summary, String bannerImage, String source, String category, List<String> topics, String sentiment, Double sentimentScore) {
        this.title = title;
        this.url = url;
        this.timePublished = timePublished;
        this.summary = summary;
        this.bannerImage = bannerImage;
        this.source = source;
        this.category = category;
        this.topics = topics;
        this.aiSentiment = sentiment;
        this.fromCache = false;
    }

    public NewsFeedItemDto(String title, String url, String timePublished, String summary, String bannerImage, String source, String category, List<String> topics, List<String> aiSummary, String aiSentiment, Integer aiConfidence, String aiReason, boolean fromCache) {
        this.title = title;
        this.url = url;
        this.timePublished = timePublished;
        this.summary = summary;
        this.bannerImage = bannerImage;
        this.source = source;
        this.category = category;
        this.topics = topics;
        this.aiSummary = aiSummary;
        this.aiSentiment = aiSentiment;
        this.aiConfidence = aiConfidence;
        this.aiReason = aiReason;
        this.fromCache = fromCache;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTimePublished() {
        return timePublished;
    }

    public void setTimePublished(String timePublished) {
        this.timePublished = timePublished;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getBannerImage() {
        return bannerImage;
    }

    public void setBannerImage(String bannerImage) {
        this.bannerImage = bannerImage;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public List<String> getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(List<String> aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getAiSentiment() {
        return aiSentiment;
    }

    public void setAiSentiment(String aiSentiment) {
        this.aiSentiment = aiSentiment;
    }

    public Integer getAiConfidence() {
        return aiConfidence;
    }

    public void setAiConfidence(Integer aiConfidence) {
        this.aiConfidence = aiConfidence;
    }

    public String getAiReason() {
        return aiReason;
    }

    public void setAiReason(String aiReason) {
        this.aiReason = aiReason;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }
}
