package com.llmgateway.dto.market;

import java.math.BigDecimal;

public class MarketPriceDto {

    private String symbol;       // "BTCUSDT", "XAUUSD", "USOIL", "ETHUSDT"
    private String name;         // "Bitcoin", "Vàng (Gold)", "Dầu thô (Crude Oil)", "Ethereum"
    private String category;     // "CRYPTO", "COMMODITY", "FOREX"
    private BigDecimal price;    // Giá hiện tại
    private BigDecimal change24h; // Tỷ lệ biến động 24h (%)
    private BigDecimal bidPrice; // Giá mua vào
    private BigDecimal askPrice; // Giá bán ra
    private String lastUpdated;  // Thời gian cập nhật

    public MarketPriceDto() {
    }

    public MarketPriceDto(String symbol, String name, String category, BigDecimal price, BigDecimal change24h, BigDecimal bidPrice, BigDecimal askPrice, String lastUpdated) {
        this.symbol = symbol;
        this.name = name;
        this.category = category;
        this.price = price;
        this.change24h = change24h;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.lastUpdated = lastUpdated;
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
}
