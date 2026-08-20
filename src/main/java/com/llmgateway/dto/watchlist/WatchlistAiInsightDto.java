package com.llmgateway.dto.watchlist;

import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO chứa thông tin tổng hợp chuyên sâu từ Gemini AI cho riêng từng mã tài sản trong Watchlist của User.
 */
public class WatchlistAiInsightDto {

    private Long watchlistId;
    private String symbol;
    private String name;
    private String category;
    private BigDecimal currentPrice;
    private BigDecimal change24h;

    // Phân tích Dự báo & Khuyến nghị chiến lược từ Gemini AI
    private ForecastResponse forecast;

    // Các bài báo tài chính liên quan đến mã này (đã được AI phân tích)
    private List<NewsFeedItemDto> relatedNews;

    public WatchlistAiInsightDto() {
    }

    public WatchlistAiInsightDto(Long watchlistId, String symbol, String name, String category, BigDecimal currentPrice, BigDecimal change24h, ForecastResponse forecast, List<NewsFeedItemDto> relatedNews) {
        this.watchlistId = watchlistId;
        this.symbol = symbol;
        this.name = name;
        this.category = category;
        this.currentPrice = currentPrice;
        this.change24h = change24h;
        this.forecast = forecast;
        this.relatedNews = relatedNews;
    }

    public Long getWatchlistId() {
        return watchlistId;
    }

    public void setWatchlistId(Long watchlistId) {
        this.watchlistId = watchlistId;
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

    public ForecastResponse getForecast() {
        return forecast;
    }

    public void setForecast(ForecastResponse forecast) {
        this.forecast = forecast;
    }

    public List<NewsFeedItemDto> getRelatedNews() {
        return relatedNews;
    }

    public void setRelatedNews(List<NewsFeedItemDto> relatedNews) {
        this.relatedNews = relatedNews;
    }
}
