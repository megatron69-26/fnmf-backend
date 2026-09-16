package com.llmgateway.service.provider;

import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.NewsArticleDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FixedMarketCacheManager {

    public static final long PRICE_TTL_MS = 30_000L; // 30 giây
    public static final long CANDLE_1M_TTL_MS = 60_000L; // 60 giây
    public static final long CANDLE_DAILY_TTL_MS = 6 * 60 * 60 * 1000L; // 6 giờ
    public static final long NEWS_TTL_MS = 6 * 60 * 60 * 1000L; // 6 giờ

    public static class CachedEntry<T> {
        private final T data;
        private final long timestamp;

        public CachedEntry(T data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }

        public T getData() {
            return data;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isFresh(long ttlMs) {
            return (System.currentTimeMillis() - timestamp) < ttlMs;
        }
    }

    private final Map<String, CachedEntry<BigDecimal>> priceCache = new ConcurrentHashMap<>();
    private final Map<String, CachedEntry<List<CandleDto>>> candleCache = new ConcurrentHashMap<>();
    private final Map<String, CachedEntry<List<NewsArticleDto>>> newsCache = new ConcurrentHashMap<>();

    public String buildPriceKey(String provider, String symbol) {
        return provider + ":" + symbol + ":price";
    }

    public String buildCandleKey(String provider, String symbol, String interval) {
        String norm = "1m".equalsIgnoreCase(interval) ? "1m" : "daily";
        return provider + ":" + symbol + ":" + norm;
    }

    public String buildNewsKey(String provider, String symbol) {
        return provider + ":" + symbol + ":news";
    }

    // Price Cache
    public void putPrice(String provider, String symbol, BigDecimal price) {
        priceCache.put(buildPriceKey(provider, symbol), new CachedEntry<>(price, System.currentTimeMillis()));
    }

    public CachedEntry<BigDecimal> getPriceEntry(String provider, String symbol) {
        return priceCache.get(buildPriceKey(provider, symbol));
    }

    // Candle Cache
    public void putCandles(String provider, String symbol, String interval, List<CandleDto> candles) {
        candleCache.put(buildCandleKey(provider, symbol, interval), new CachedEntry<>(candles, System.currentTimeMillis()));
    }

    public CachedEntry<List<CandleDto>> getCandleEntry(String provider, String symbol, String interval) {
        return candleCache.get(buildCandleKey(provider, symbol, interval));
    }

    public long resolveCandleTtl(String interval) {
        return "1m".equalsIgnoreCase(interval) ? CANDLE_1M_TTL_MS : CANDLE_DAILY_TTL_MS;
    }

    // News Cache
    public void putNews(String provider, String symbol, List<NewsArticleDto> news) {
        newsCache.put(buildNewsKey(provider, symbol), new CachedEntry<>(news, System.currentTimeMillis()));
    }

    public CachedEntry<List<NewsArticleDto>> getNewsEntry(String provider, String symbol) {
        return newsCache.get(buildNewsKey(provider, symbol));
    }

    public void clearAll() {
        priceCache.clear();
        candleCache.clear();
        newsCache.clear();
    }
}
