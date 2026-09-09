package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.exception.MarketDataUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final BinanceMarketClient binanceMarketClient;

    @Value("${alphavantage.api.key:}")
    private String apiKey;

    @Value("${alphavantage.api.url:https://www.alphavantage.co/query}")
    private String apiUrl;

    // ====================================================================================
    // 🛡️ BỘ NHỚ ĐỆM (IN-MEMORY CACHE) THỜI GIAN THỰC (ZERO FAKE DATA)
    // ------------------------------------------------------------------------------------
    // Chỉ lưu trữ dữ liệu thật từ Binance Provider. Tuyệt đối không sinh giá ngẫu nhiên hay
    // nến giả lập (Math.random).
    // ====================================================================================
    private final Map<String, MarketPriceDto> priceCache = new ConcurrentHashMap<>();
    private final Map<String, List<CandleDto>> candleCache = new ConcurrentHashMap<>();
    private final List<NewsFeedItemDto> newsFeedCache = new ArrayList<>();
    private long lastPriceFetchTime = 0;
    private long lastNewsFetchTime = 0;
    private static final long PRICE_CACHE_TTL_MS = 15_000; // 15 giây
    private static final long NEWS_CACHE_TTL_MS = 120_000; // 2 phút

    public MarketDataService(ObjectMapper objectMapper, BinanceMarketClient binanceMarketClient) {
        this.objectMapper = objectMapper;
        this.binanceMarketClient = binanceMarketClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Lấy giá thời gian thực của các tài sản chuẩn hóa (BTCUSDT, ETHUSDT, XAUUSD).
     * Một symbol lỗi không được làm mất các symbol còn hoạt động.
     * /api/market/prices trả danh sách các symbol lấy thành công.
     * Chỉ trả 503 khi toàn bộ provider đều lỗi và không có cache thật nào khả dụng.
     */
    public List<MarketPriceDto> getAllPrices() {
        long now = System.currentTimeMillis();

        if (now - lastPriceFetchTime < PRICE_CACHE_TTL_MS && priceCache.size() >= MarketSymbolConfig.getAllCanonical().size()) {
            return new ArrayList<>(priceCache.values());
        }

        List<MarketPriceDto> results = new ArrayList<>();

        for (MarketSymbolConfig.SymbolMeta meta : MarketSymbolConfig.getAllCanonical()) {
            try {
                MarketPriceDto dto = fetchOrCachePrice(meta);
                if (dto != null) {
                    results.add(dto);
                }
            } catch (Exception e) {
                log.warn("Lỗi khi xử lý giá cho mã {}: {}", meta.canonicalSymbol(), e.getMessage());
                if (priceCache.containsKey(meta.canonicalSymbol())) {
                    results.add(createStaleDto(priceCache.get(meta.canonicalSymbol())));
                }
            }
        }

        if (results.isEmpty()) {
            throw new MarketDataUnavailableException("Dữ liệu thị trường thời gian thực tạm thời không khả dụng");
        }

        lastPriceFetchTime = now;
        return results;
    }

    /**
     * Lấy giá 1 mã tài sản cụ thể.
     * Ném UnsupportedSymbolException (HTTP 422) nếu mã không được hỗ trợ (hoặc USOIL).
     * Ném MarketDataUnavailableException (HTTP 503) nếu không lấy được giá và chưa có cache thật.
     */
    public MarketPriceDto getPriceBySymbol(String symbol) {
        MarketSymbolConfig.validateSupported(symbol);
        String canonicalSymbol = MarketSymbolConfig.getCanonicalSymbol(symbol);
        MarketSymbolConfig.SymbolMeta meta = MarketSymbolConfig.getMeta(canonicalSymbol);

        long now = System.currentTimeMillis();
        if (now - lastPriceFetchTime < PRICE_CACHE_TTL_MS && priceCache.containsKey(canonicalSymbol)) {
            return priceCache.get(canonicalSymbol);
        }

        MarketPriceDto dto = fetchOrCachePrice(meta);
        if (dto != null) {
            return dto;
        }

        throw new MarketDataUnavailableException("Dữ liệu thị trường tạm thời không khả dụng cho mã: " + canonicalSymbol);
    }

    /**
     * Lấy chuỗi nến OHLC thật từ Binance cho biểu đồ.
     * Ném UnsupportedSymbolException (HTTP 422) nếu mã không hỗ trợ.
     * Ném MarketDataUnavailableException (HTTP 503) nếu provider lỗi và không có cache nến thật.
     * Tuyệt đối KHÔNG sinh nến giả (zero-fake).
     */
    public List<CandleDto> getCandles(String symbol, String interval) {
        MarketSymbolConfig.validateSupported(symbol);
        String canonicalSymbol = MarketSymbolConfig.getCanonicalSymbol(symbol);
        MarketSymbolConfig.SymbolMeta meta = MarketSymbolConfig.getMeta(canonicalSymbol);

        String cacheKey = canonicalSymbol + "_" + (interval != null ? interval.toLowerCase() : "daily");

        try {
            List<CandleDto> candles = binanceMarketClient.fetchKlines(meta.binanceSymbol(), interval, 30);
            if (!candles.isEmpty()) {
                candleCache.put(cacheKey, candles);
                return candles;
            }
        } catch (Exception e) {
            log.warn("Lỗi khi tải nến từ Binance cho mã {}: {}", canonicalSymbol, e.getMessage());
        }

        if (candleCache.containsKey(cacheKey) && !candleCache.get(cacheKey).isEmpty()) {
            log.info("SỬ DỤNG CACHE NẾN THẬT CHO MÃ {}", canonicalSymbol);
            return candleCache.get(cacheKey);
        }

        throw new MarketDataUnavailableException("Dữ liệu nến tạm thời không khả dụng cho mã: " + canonicalSymbol);
    }

    /**
     * Lấy dòng tin tức tài chính (News Feed) từ Alpha Vantage.
     */
    public synchronized List<NewsFeedItemDto> getNewsFeed(int limit) {
        long now = System.currentTimeMillis();
        if (now - lastNewsFetchTime < NEWS_CACHE_TTL_MS && !newsFeedCache.isEmpty()) {
            return newsFeedCache.stream().limit(limit > 0 ? limit : 10).toList();
        }

        List<NewsFeedItemDto> items = fetchNewsFromApi(limit > 0 ? limit : 10);
        if (!items.isEmpty()) {
            newsFeedCache.clear();
            newsFeedCache.addAll(items);
            lastNewsFetchTime = now;
        }

        return newsFeedCache;
    }

    // =========================================================================
    // TESTING HELPERS & CACHE MANAGEMENT
    // =========================================================================

    public void clearCache() {
        priceCache.clear();
        candleCache.clear();
        newsFeedCache.clear();
        lastPriceFetchTime = 0;
        lastNewsFetchTime = 0;
    }

    public void putPriceInCache(MarketPriceDto dto) {
        priceCache.put(dto.getSymbol(), dto);
    }

    public Map<String, MarketPriceDto> getPriceCache() {
        return priceCache;
    }

    // =========================================================================
    // PRIVATE HELPER METHODS (ZERO FAKE DATA)
    // =========================================================================

    private MarketPriceDto fetchOrCachePrice(MarketSymbolConfig.SymbolMeta meta) {
        String canonical = meta.canonicalSymbol();
        try {
            BinanceMarketClient.BinanceTickerResult ticker = binanceMarketClient.fetch24hrTicker(meta.binanceSymbol());
            String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            MarketPriceDto dto = new MarketPriceDto(
                    canonical,
                    meta.name(),
                    meta.category(),
                    ticker.price(),
                    ticker.change24h(),
                    ticker.bidPrice(),
                    ticker.askPrice(),
                    nowStr,
                    false,
                    "BINANCE",
                    nowStr
            );
            priceCache.put(canonical, dto);
            log.info("FETCHED LIVE BINANCE PRICE | symbol={} | price={}", canonical, ticker.price());
            return dto;
        } catch (Exception e) {
            log.warn("Không thể tải giá thời gian thực từ Binance cho {}: {}", canonical, e.getMessage());
        }

        // Provider failure policy: Return cache with stale=true if real cache exists
        if (priceCache.containsKey(canonical)) {
            MarketPriceDto cached = priceCache.get(canonical);
            MarketPriceDto staleDto = createStaleDto(cached);
            log.warn("SỬ DỤNG GIÁ CACHE CHO MÃ (STALE) | symbol={} | price={}", canonical, staleDto.getPrice());
            return staleDto;
        }

        return null;
    }

    private MarketPriceDto createStaleDto(MarketPriceDto cached) {
        return new MarketPriceDto(
                cached.getSymbol(),
                cached.getName(),
                cached.getCategory(),
                cached.getPrice(),
                cached.getChange24h(),
                cached.getBidPrice(),
                cached.getAskPrice(),
                cached.getLastUpdated(),
                true,
                "CACHE_BINANCE",
                cached.getFetchedAt()
        );
    }

    private List<NewsFeedItemDto> fetchNewsFromApi(int limit) {
        List<NewsFeedItemDto> list = new ArrayList<>();
        if (apiKey == null || apiKey.isBlank()) {
            return list;
        }
        try {
            String url = String.format("%s?function=NEWS_SENTIMENT&topics=financial_markets,technology&limit=%d&apikey=%s",
                    apiUrl, limit > 0 ? limit : 10, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode feed = root.path("feed");

                if (feed.isArray()) {
                    for (JsonNode node : feed) {
                        String title = node.path("title").asText();
                        String articleUrl = node.path("url").asText();
                        String timePublished = node.path("time_published").asText();
                        String summary = node.path("summary").asText();
                        String bannerImage = node.path("banner_image").asText(null);
                        String source = node.path("source").asText("Financial News");
                        String category = node.path("category_within_source").asText("Market");
                        String sentiment = node.path("overall_sentiment_label").asText("Neutral");
                        Double score = node.path("overall_sentiment_score").asDouble(0.0);

                        List<String> topics = new ArrayList<>();
                        for (JsonNode t : node.path("topics")) {
                            topics.add(t.path("topic").asText());
                        }

                        list.add(new NewsFeedItemDto(title, articleUrl, timePublished, summary, bannerImage, source, category, topics, sentiment, score));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi tải News Feed từ Alpha Vantage: {}", e.getMessage());
        }
        return list;
    }
}
