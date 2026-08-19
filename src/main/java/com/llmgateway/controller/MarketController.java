package com.llmgateway.controller;

import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.service.MarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /**
     * GET /api/market/prices
     * Lấy giá thời gian thực của tất cả các tài sản chính (Bitcoin, Vàng XAUUSD, Dầu USOIL, Ethereum).
     */
    @GetMapping("/prices")
    public ResponseEntity<List<MarketPriceDto>> getAllPrices() {
        List<MarketPriceDto> prices = marketDataService.getAllPrices();
        return ResponseEntity.ok(prices);
    }

    /**
     * GET /api/market/price/{symbol}
     * Lấy giá của 1 mã tài sản cụ thể (ví dụ: /api/market/price/BTCUSDT)
     */
    @GetMapping("/price/{symbol}")
    public ResponseEntity<MarketPriceDto> getPrice(@PathVariable String symbol) {
        MarketPriceDto price = marketDataService.getPriceBySymbol(symbol);
        return ResponseEntity.ok(price);
    }

    /**
     * GET /api/market/candles?symbol=BTCUSDT&interval=daily
     * Lấy chuỗi nến OHLC (Open, High, Low, Close, Volume) để Android vẽ Candlestick Chart.
     */
    @GetMapping("/candles")
    public ResponseEntity<List<CandleDto>> getCandles(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "daily") String interval) {
        List<CandleDto> candles = marketDataService.getCandles(symbol, interval);
        return ResponseEntity.ok(candles);
    }

    /**
     * GET /api/market/news?limit=10
     * Lấy dòng tin tức tài chính mới nhất từ Alpha Vantage.
     */
    @GetMapping("/news")
    public ResponseEntity<List<NewsFeedItemDto>> getNews(@RequestParam(defaultValue = "10") int limit) {
        List<NewsFeedItemDto> news = marketDataService.getNewsFeed(limit);
        return ResponseEntity.ok(news);
    }
}
