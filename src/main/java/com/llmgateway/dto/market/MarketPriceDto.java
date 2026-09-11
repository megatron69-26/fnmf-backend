package com.llmgateway.dto.market;

import java.math.BigDecimal;

public class MarketPriceDto {

    private String symbol;       // "BTCUSDT", "ETHUSDT", "XAUUSD"
    private String name;         // "Bitcoin", "Ethereum", "Vàng (Gold Spot)"
    private String category;     // "CRYPTO", "COMMODITY"
    private BigDecimal price;    // Giá hiện tại
    private BigDecimal change24h; // Tỷ lệ biến động 24h (%)
    private BigDecimal bidPrice; // Giá mua vào
    private BigDecimal askPrice; // Giá bán ra
    private String lastUpdated;  // Thời gian cập nhật hiển thị
    private Boolean stale = false; // true nếu lấy từ bộ nhớ đệm khi provider lỗi
    private String source = "BINANCE"; // "BINANCE", "CACHE_BINANCE"
    private String fetchedAt;    // Thời điểm truy xuất ISO-8601

    public MarketPriceDto() {
    }

    public MarketPriceDto(String symbol, String name, String category, BigDecimal price, BigDecimal change24h, BigDecimal bidPrice, BigDecimal askPrice, String lastUpdated) {
        this(symbol, name, category, price, change24h, bidPrice, askPrice, lastUpdated, false, "BINANCE", lastUpdated);
    }

    public MarketPriceDto(String symbol, String name, String category, BigDecimal price, BigDecimal change24h, BigDecimal bidPrice, BigDecimal askPrice, String lastUpdated, Boolean stale, String source, String fetchedAt) {
        this.symbol = symbol;
        this.name = name;
        this.category = category;
        this.price = price;
        this.change24h = change24h;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.lastUpdated = lastUpdated;
        this.stale = stale != null ? stale : false;
        this.source = source != null ? source : "BINANCE";
        this.fetchedAt = fetchedAt != null ? fetchedAt : lastUpdated;
    }

    public MarketPriceDto(String symbol, String name, BigDecimal price, BigDecimal change24h, Boolean stale, String source) {
        this(symbol, name, "CRYPTO", price, change24h, price, price, "2026-09-11T12:00:00", stale, source, "2026-09-11T12:00:00");
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getChange24h() {
        return change24h;
    }

    public void setChange24h(BigDecimal change24h) {
        this.change24h = change24h;
    }

    public BigDecimal getBidPrice() {
        return bidPrice;
    }

    public void setBidPrice(BigDecimal bidPrice) {
        this.bidPrice = bidPrice;
    }

    public BigDecimal getAskPrice() {
        return askPrice;
    }

    public void setAskPrice(BigDecimal askPrice) {
        this.askPrice = askPrice;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Boolean isStale() {
        return stale;
    }

    public Boolean getStale() {
        return stale;
    }

    public void setStale(Boolean stale) {
        this.stale = stale;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(String fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
