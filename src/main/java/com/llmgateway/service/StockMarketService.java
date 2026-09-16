package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.market.NewsArticleDto;
import com.llmgateway.dto.stock.StockCatalogDto;
import com.llmgateway.dto.stock.StockDetailDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.service.provider.AlpacaStockDataProvider;
import com.llmgateway.service.provider.AlphaVantageStockDataProvider;
import com.llmgateway.service.provider.BinanceMarketDataProvider;
import com.llmgateway.service.provider.FixedMarketCacheManager;
import com.llmgateway.service.provider.FixedMarketProviderRouter;
import com.llmgateway.service.provider.GeminiShardRouter;
import com.llmgateway.service.provider.MarketContentProvider;
import com.llmgateway.service.provider.TwelveDataStockDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service quản lý dữ liệu 8 mã cổ phiếu Mỹ qua Fixed Provider Sharding và Gemini Sharding.
 * - AAPL, MSFT, NVDA, GOOGL -> Alpaca (feed=iex, sort=desc)
 * - TSLA, AMZN, META, JPM -> Twelve Data (/time_series)
 * - Gemini Shard 2 -> AAPL, MSFT, NVDA, GOOGL
 * - Gemini Shard 3 -> TSLA, AMZN, META, JPM
 *
 * Cache chính xác:
 * - Giá: 30 giây
 * - Nến 1 phút: 60 giây
 * - Nến ngày: 6 giờ
 * - Tin tức: 6 giờ
 * - Khuyến nghị: 24 giờ
 */
@Service
public class StockMarketService {

    private static final Logger log = LoggerFactory.getLogger(StockMarketService.class);

    public static final long STOCK_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    public static final long RECOMMENDATION_TTL_MS = 24 * 60 * 60 * 1000L;
    public static final long GENERAL_ERROR_COOLDOWN_MS = 15 * 60 * 1000L;
    public static final long RATE_LIMIT_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    public static final String REC_CONSIDER_BUY = "Nên cân nhắc mua";
    public static final String REC_AVOID_BUY = "Chưa nên mua";

    private final ObjectMapper objectMapper;
    private final NewsAiCacheRepository newsAiCacheRepository;
    private final HttpClient httpClient;
    private final FixedMarketProviderRouter fixedMarketProviderRouter;
    private final FixedMarketCacheManager fixedMarketCacheManager;
    private final GeminiShardRouter geminiShardRouter;

    @Value("${alphavantage.api.key:${ALPHAVANTAGE_API_KEY:}}")
    private String alphaVantageKey;

    @Value("${alphavantage.api.url:https://www.alphavantage.co/query}")
    private String alphaVantageUrl = "https://www.alphavantage.co/query";

    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String geminiApiKey;

    @Value("${openai.api.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}")
    private String geminiApiUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

    @Value("${openai.default-model:gemini-3.6-flash}")
    private String geminiModel = "gemini-3.6-flash";

    private final Map<String, CachedStockData> stockCache = new ConcurrentHashMap<>();
    private final Map<String, CachedStockData> stockIntervalCache = new ConcurrentHashMap<>();
    private final Map<String, Long> symbolCooldownMap = new ConcurrentHashMap<>();
    private final Map<String, Long> geminiAttemptTimestampMap = new ConcurrentHashMap<>();

    @Autowired
    public StockMarketService(ObjectMapper objectMapper,
                              NewsAiCacheRepository newsAiCacheRepository,
                              FixedMarketProviderRouter fixedMarketProviderRouter,
                              FixedMarketCacheManager fixedMarketCacheManager,
                              GeminiShardRouter geminiShardRouter) {
        this(objectMapper, newsAiCacheRepository, fixedMarketProviderRouter, fixedMarketCacheManager, geminiShardRouter,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public StockMarketService(ObjectMapper objectMapper,
                              NewsAiCacheRepository newsAiCacheRepository,
                              FixedMarketProviderRouter fixedMarketProviderRouter,
                              FixedMarketCacheManager fixedMarketCacheManager,
                              GeminiShardRouter geminiShardRouter,
                              HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.fixedMarketProviderRouter = fixedMarketProviderRouter;
        this.fixedMarketCacheManager = fixedMarketCacheManager;
        this.geminiShardRouter = geminiShardRouter;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public StockMarketService(ObjectMapper objectMapper, NewsAiCacheRepository newsAiCacheRepository) {
        this(objectMapper, newsAiCacheRepository, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public StockMarketService(ObjectMapper objectMapper,
                              NewsAiCacheRepository newsAiCacheRepository,
                              HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.httpClient = httpClient;
        this.fixedMarketCacheManager = null;
        this.fixedMarketProviderRouter = null;
        this.geminiShardRouter = null;
    }

    /**
     * Danh mục 8 cổ phiếu cố định (symbol + name). Không gọi provider bên ngoài.
     */
    public List<StockCatalogDto> getStockCatalog() {
        return MarketSymbolConfig.getStockCatalog();
    }

    /**
     * Lấy dữ liệu trong cache nếu có (phục vụ GET Watchlist không gọi hàng loạt provider).
     */
    public CachedStockData getCachedStock(String symbol) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        CachedStockData cached = stockCache.get(canonical);
        if (cached == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long ttl = (fixedMarketProviderRouter != null) ? FixedMarketCacheManager.PRICE_TTL_MS : STOCK_CACHE_TTL_MS;
        boolean isFresh = (now - cached.getFetchedTimestamp() < ttl);
        cached.setStale(!isFresh);
        if (cached.getLatestReportTitle() == null) {
            populateLatestReport(cached, canonical);
        }
        return cached;
    }

    public CachedStockData fetchAndCacheStock(String symbol) {
        if (fixedMarketProviderRouter != null) {
            return fetchAndCacheStock(symbol, "daily");
        }
        return fetchAndCacheStockAlphaVantage(symbol);
    }

    /**
     * Tải và cache dữ liệu cổ phiếu qua FixedMarketProviderRouter và Gemini Shards.
     */
    public synchronized CachedStockData fetchAndCacheStock(String symbol, String interval) {
        if (fixedMarketProviderRouter == null) {
            return fetchAndCacheStockAlphaVantage(symbol);
        }
        MarketSymbolConfig.validateSupported(symbol);
        if (!MarketSymbolConfig.isStock(symbol)) {
            throw new IllegalArgumentException("Mã " + symbol + " không phải là cổ phiếu");
        }
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        MarketSymbolConfig.SymbolMeta meta = MarketSymbolConfig.getMeta(canonical);
        String normInterval = "1m".equalsIgnoreCase(interval) ? "1m" : "daily";
        String intervalKey = canonical + "#" + normInterval;

        long now = System.currentTimeMillis();
        CachedStockData existingInterval = stockIntervalCache.get(intervalKey);

        MarketContentProvider provider = fixedMarketProviderRouter.getProvider(canonical);
        String providerName = provider.providerName();

        // 1. Kiểm tra cache giá (30s) và nến (60s cho 1m, 6h cho daily)
        FixedMarketCacheManager.CachedEntry<BigDecimal> cachedPrice = fixedMarketCacheManager.getPriceEntry(providerName, canonical);
        FixedMarketCacheManager.CachedEntry<List<CandleDto>> cachedCandles = fixedMarketCacheManager.getCandleEntry(providerName, canonical, normInterval);

        long candleTtl = fixedMarketCacheManager.resolveCandleTtl(normInterval);
        boolean priceFresh = cachedPrice != null && cachedPrice.isFresh(FixedMarketCacheManager.PRICE_TTL_MS);
        boolean candlesFresh = cachedCandles != null && cachedCandles.isFresh(candleTtl);

        if (existingInterval != null && priceFresh && candlesFresh) {
            log.debug("SỬ DỤNG CACHE TƯƠI CHO MÃ {} | interval={} | provider={}", canonical, normInterval, providerName);
            existingInterval.setStale(false);
            if (existingInterval.getLatestReportTitle() == null) {
                populateLatestReport(existingInterval, canonical);
            }
            return existingInterval;
        }

        // 2. Fetch dữ liệu thật từ Provider cố định
        try {
            List<CandleDto> candles = provider.getCandles(canonical, normInterval, 30);
            BigDecimal currentPrice;
            if (!candles.isEmpty()) {
                currentPrice = candles.get(candles.size() - 1).getClose().setScale(2, RoundingMode.HALF_UP);
            } else {
                currentPrice = provider.getLatestPrice(canonical).setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal change24h = BigDecimal.ZERO;
            if (candles.size() >= 2) {
                BigDecimal prevClose = candles.get(candles.size() - 2).getClose();
                if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                    change24h = currentPrice.subtract(prevClose)
                            .divide(prevClose, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }

            String priceAsOf = (!candles.isEmpty()) ? candles.get(candles.size() - 1).getTime() : String.valueOf(now);

            // Fetch tin tức từ provider nếu chưa có trong cache 6h (Blocker 4)
            FixedMarketCacheManager.CachedEntry<List<NewsArticleDto>> cachedNewsEntry = fixedMarketCacheManager.getNewsEntry(providerName, canonical);
            List<NewsArticleDto> providerNews;
            if (cachedNewsEntry != null && cachedNewsEntry.isFresh(FixedMarketCacheManager.NEWS_TTL_MS)) {
                providerNews = cachedNewsEntry.getData();
            } else {
                try {
                    providerNews = provider.getLatestNews(canonical, 3);
                    if (providerNews == null) {
                        providerNews = Collections.emptyList();
                    }
                } catch (Exception e) {
                    log.warn("Lỗi khi tải tin tức từ provider {} cho mã {}: {}", providerName, canonical, e.getClass().getSimpleName());
                    providerNews = Collections.emptyList();
                }
                fixedMarketCacheManager.putNews(providerName, canonical, providerNews);
            }

            // Lưu vào FixedMarketCacheManager
            fixedMarketCacheManager.putPrice(providerName, canonical, currentPrice);
            if (!candles.isEmpty()) {
                fixedMarketCacheManager.putCandles(providerName, canonical, normInterval, candles);
            }

            CachedStockData freshData = new CachedStockData(
                    canonical,
                    meta.name(),
                    currentPrice,
                    change24h,
                    priceAsOf,
                    candles,
                    now,
                    false
            );
            freshData.setMarketDataProvider(providerName);
            freshData.setCandleCount(candles.size());

            String shardName = geminiShardRouter.resolveShardName(canonical);
            freshData.setAiShard(shardName);

            // Giữ lại khuyến nghị cũ nếu còn trong 24h (kể cả khi lần trước thất bại/null để chống spam Gemini mỗi phút)
            Long lastGeminiAttempt = geminiAttemptTimestampMap.get(canonical);
            CachedStockData existingStock = stockCache.get(canonical);
            if (lastGeminiAttempt != null && (now - lastGeminiAttempt < RECOMMENDATION_TTL_MS)) {
                String cachedRec = (existingStock != null) ? existingStock.getRecommendation() : null;
                freshData.setRecommendation(cachedRec);
                freshData.setRecommendationTimestamp(lastGeminiAttempt);
            } else {
                geminiAttemptTimestampMap.put(canonical, now);
                // Tạo khuyến nghị mới qua Gemini Shard tương ứng (không fallback chéo)
                String rec = generateRecommendationWithGemini(canonical, meta.name(), currentPrice, candles, providerNews);
                freshData.setRecommendation(rec);
                freshData.setRecommendationTimestamp(now);
            }

            // Bài báo thực tế
            populateLatestReport(freshData, canonical, providerNews);

            stockIntervalCache.put(intervalKey, freshData);
            stockCache.put(canonical, freshData);
            log.info("NẠP THÀNH CÔNG DỮ LIỆU THẬT CỔ PHIẾU | symbol={} | interval={} | provider={} | shard={} | price={}",
                    canonical, normInterval, providerName, shardName, currentPrice);
            return freshData;
        } catch (Exception e) {
            log.warn("Lỗi khi tải dữ liệu từ provider {} cho mã {}: {}", providerName, canonical, e.getClass().getSimpleName());
        }

        // 3. Fallback: Nếu có cache thật cũ của ĐÚNG interval yêu cầu -> dùng cache với stale=true
        if (existingInterval != null) {
            log.warn("SỬ DỤNG CACHE CỔ PHIẾU CŨ (STALE=TRUE) CHO MÃ {} | interval={}", canonical, normInterval);
            existingInterval.setStale(true);
            return existingInterval;
        }

        if (cachedCandles != null && cachedCandles.getData() != null && !cachedCandles.getData().isEmpty()) {
            BigDecimal stalePrice = (cachedPrice != null && cachedPrice.getData() != null)
                    ? cachedPrice.getData()
                    : cachedCandles.getData().get(cachedCandles.getData().size() - 1).getClose();
            CachedStockData staleData = new CachedStockData(
                    canonical,
                    meta.name(),
                    stalePrice,
                    BigDecimal.ZERO,
                    cachedCandles.getData().get(cachedCandles.getData().size() - 1).getTime(),
                    cachedCandles.getData(),
                    cachedCandles.getTimestamp(),
                    true
            );
            staleData.setMarketDataProvider(providerName);
            staleData.setCandleCount(cachedCandles.getData().size());
            populateLatestReport(staleData, canonical);
            stockIntervalCache.put(intervalKey, staleData);
            return staleData;
        }

        // 4. Nếu không có cache của đúng interval: HTTP 503 (không trả interval khác)
        throw new MarketDataUnavailableException("Dữ liệu thị trường tạm thời không khả dụng cho cổ phiếu: " + canonical);
    }

    /**
     * Tải và cache dữ liệu cổ phiếu thật cho đúng 1 mã yêu cầu dùng Alpha Vantage (chế độ test/legacy).
     */
    public synchronized CachedStockData fetchAndCacheStockAlphaVantage(String symbol) {
        MarketSymbolConfig.validateSupported(symbol);
        if (!MarketSymbolConfig.isStock(symbol)) {
            throw new IllegalArgumentException("Mã " + symbol + " không phải là cổ phiếu");
        }
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        MarketSymbolConfig.SymbolMeta meta = MarketSymbolConfig.getMeta(canonical);

        long now = System.currentTimeMillis();
        CachedStockData existing = stockCache.get(canonical);

        // 1. Kiểm tra cache 24 giờ còn tươi (stale=false)
        if (existing != null && (now - existing.getFetchedTimestamp() < STOCK_CACHE_TTL_MS)) {
            log.debug("SỬ DỤNG CACHE CỔ PHIẾU 24H CHO MÃ {} (còn tươi)", canonical);
            existing.setStale(false);
            if (existing.getLatestReportTitle() == null) {
                populateLatestReport(existing, canonical);
            }
            return existing;
        }

        // 1b. Kiểm tra cooldown theo symbol: trong cooldown không gọi lại Alpha Vantage
        Long cooldownUntil = symbolCooldownMap.get(canonical);
        if (cooldownUntil != null && now < cooldownUntil) {
            log.warn("Mã {} đang trong thời gian cooldown, không gọi lại Alpha Vantage", canonical);
            if (existing != null) {
                existing.setStale(true);
                return existing;
            }
            throw new MarketDataUnavailableException("Dữ liệu thị trường tạm thời không khả dụng do đang trong thời gian chờ (cooldown): " + canonical);
        }

        // 2. Gọi Alpha Vantage TIME_SERIES_DAILY cho mã này (không log URL chứa apikey, không log response body)
        try {
            if (alphaVantageKey != null && !alphaVantageKey.isBlank() && !alphaVantageKey.startsWith("${")) {
                String baseUrl = (alphaVantageUrl != null && !alphaVantageUrl.isBlank()) ? alphaVantageUrl : "https://www.alphavantage.co/query";
                String url = String.format("%s?function=TIME_SERIES_DAILY&symbol=%s&outputsize=compact&apikey=%s",
                        baseUrl, canonical, alphaVantageKey);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 429) {
                    log.warn("Alpha Vantage trả HTTP 429 (Rate Limit) cho mã {}, kích hoạt cooldown 24h", canonical);
                    symbolCooldownMap.put(canonical, now + RATE_LIMIT_COOLDOWN_MS);
                } else if (statusCode == 200 && response.body() != null && !response.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(response.body());

                    if (root.has("Note") || root.has("Information")) {
                        log.warn("Alpha Vantage trả thông báo giới hạn tần suất (Note/Information) cho mã {}, kích hoạt cooldown 24h", canonical);
                        symbolCooldownMap.put(canonical, now + RATE_LIMIT_COOLDOWN_MS);
                    } else if (root.has("Error Message")) {
                        log.warn("Alpha Vantage trả thông báo lỗi (Error Message) cho mã {}, kích hoạt cooldown 15m", canonical);
                        symbolCooldownMap.put(canonical, now + GENERAL_ERROR_COOLDOWN_MS);
                    } else {
                        JsonNode timeSeries = root.path("Time Series (Daily)");
                        if (timeSeries.isObject() && !timeSeries.isEmpty()) {
                            CachedStockData freshData = parseAlphaVantageTimeSeries(canonical, meta.name(), timeSeries, now);
                            freshData.setStale(false);

                            // Giữ lại khuyến nghị cũ nếu còn trong 24h
                            if (existing != null && existing.getRecommendation() != null &&
                                    (now - existing.getRecommendationTimestamp() < RECOMMENDATION_TTL_MS)) {
                                freshData.setRecommendation(existing.getRecommendation());
                                freshData.setRecommendationTimestamp(existing.getRecommendationTimestamp());
                            } else {
                                // Tạo khuyến nghị mới qua Gemini (kết hợp nến và tin tức thật từ NEWS_AI_CACHE nếu có)
                                String rec = generateRecommendationWithGemini(canonical, meta.name(), freshData.getCurrentPrice(), freshData.getCandles(), Collections.emptyList());
                                freshData.setRecommendation(rec);
                                freshData.setRecommendationTimestamp(now);
                            }

                            // Tìm bài báo thực tế mới nhất từ CSDL News
                            populateLatestReport(freshData, canonical);

                            stockCache.put(canonical, freshData);
                            symbolCooldownMap.remove(canonical);
                            log.info("NẠP THÀNH CÔNG DỮ LIỆU THẬT CỔ PHIẾU | symbol={} | price={} | asOf={}",
                                    canonical, freshData.getCurrentPrice(), freshData.getPriceAsOf());
                            return freshData;
                        } else {
                            log.warn("Alpha Vantage không trả về Time Series (Daily) hợp lệ cho mã {}", canonical);
                            symbolCooldownMap.put(canonical, now + GENERAL_ERROR_COOLDOWN_MS);
                        }
                    }
                } else {
                    log.warn("Alpha Vantage trả HTTP status {} cho mã {}", statusCode, canonical);
                    symbolCooldownMap.put(canonical, now + GENERAL_ERROR_COOLDOWN_MS);
                }
            } else {
                log.warn("Chưa cấu hình Alpha Vantage API key cho dịch vụ cổ phiếu");
                symbolCooldownMap.put(canonical, now + GENERAL_ERROR_COOLDOWN_MS);
            }
        } catch (Exception e) {
            log.warn("Lỗi khi tải dữ liệu cổ phiếu từ Alpha Vantage cho mã {}: {}", canonical, e.getClass().getSimpleName());
            symbolCooldownMap.put(canonical, now + GENERAL_ERROR_COOLDOWN_MS);
        }

        // 3. Fallback: Nếu provider lỗi nhưng có cache thật cũ -> dùng cache với stale=true
        if (existing != null) {
            log.warn("SỬ DỤNG CACHE CỔ PHIẾU CŨ (STALE=TRUE) CHO MÃ {}", canonical);
            existing.setStale(true);
            return existing;
        }

        // 4. Nếu hoàn toàn không có dữ liệu thật/cache: ném ngoại lệ 503, tuyệt đối không sinh số giả
        throw new MarketDataUnavailableException("Dữ liệu thị trường tạm thời không khả dụng cho cổ phiếu: " + canonical);
    }

    private CachedStockData parseAlphaVantageTimeSeries(String symbol, String name, JsonNode timeSeries, long fetchTime) {
        List<String> dates = new ArrayList<>();
        Iterator<String> fieldNames = timeSeries.fieldNames();
        while (fieldNames.hasNext()) {
            dates.add(fieldNames.next());
        }
        Collections.sort(dates);

        if (dates.isEmpty()) {
            throw new IllegalArgumentException("Không có dữ liệu nến cho cổ phiếu " + symbol);
        }

        List<CandleDto> allCandles = new ArrayList<>(dates.size());
        for (String dateStr : dates) {
            JsonNode dayNode = timeSeries.path(dateStr);
            allCandles.add(validateAndBuildCandle(dateStr, dayNode));
        }

        int startIndex = Math.max(0, allCandles.size() - 30);
        List<CandleDto> candles = new ArrayList<>(allCandles.subList(startIndex, allCandles.size()));

        CandleDto latestCandle = allCandles.get(allCandles.size() - 1);
        BigDecimal currentPrice = latestCandle.getClose().setScale(2, RoundingMode.HALF_UP);

        BigDecimal change24h = BigDecimal.ZERO;
        if (allCandles.size() >= 2) {
            CandleDto prevCandle = allCandles.get(allCandles.size() - 2);
            BigDecimal prevClose = prevCandle.getClose();
            if (prevClose.compareTo(BigDecimal.ZERO) > 0) {
                change24h = currentPrice.subtract(prevClose)
                        .divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }

        return new CachedStockData(
                symbol,
                name,
                currentPrice,
                change24h,
                latestCandle.getTime(),
                candles,
                fetchTime,
                false
        );
    }

    public MarketPriceDto getStockPrice(String symbol) {
        if (fixedMarketProviderRouter == null) {
            CachedStockData data = fetchAndCacheStockAlphaVantage(symbol);
            String nowStr = data.getPriceAsOf();
            return new MarketPriceDto(
                    data.getSymbol(),
                    data.getName(),
                    "STOCK",
                    data.getCurrentPrice(),
                    data.getChange24h(),
                    data.getCurrentPrice(),
                    data.getCurrentPrice(),
                    nowStr,
                    data.isStale(),
                    data.isStale() ? "CACHE_ALPHA_VANTAGE" : "ALPHA_VANTAGE",
                    nowStr
            );
        }
        CachedStockData data = fetchAndCacheStock(symbol, "1m");
        String nowStr = data.getPriceAsOf() != null ? data.getPriceAsOf() : String.valueOf(System.currentTimeMillis());
        String provider = data.getMarketDataProvider() != null ? data.getMarketDataProvider() : "STOCK";
        String source = data.isStale() ? "CACHE_" + provider : provider;

        return new MarketPriceDto(
                data.getSymbol(),
                data.getName(),
                "STOCK",
                data.getCurrentPrice(),
                data.getChange24h(),
                data.getCurrentPrice(),
                data.getCurrentPrice(),
                nowStr,
                data.isStale(),
                source,
                nowStr
        );
    }

    public List<CandleDto> getStockCandles(String symbol) {
        return getStockCandles(symbol, "daily");
    }

    public List<CandleDto> getStockCandles(String symbol, String interval) {
        if (fixedMarketProviderRouter == null) {
            CachedStockData data = fetchAndCacheStockAlphaVantage(symbol);
            return data.getCandles();
        }
        CachedStockData data = fetchAndCacheStock(symbol, interval);
        return data.getCandles();
    }

    public StockDetailDto getStockDetail(String symbol) {
        if (fixedMarketProviderRouter == null) {
            CachedStockData data = fetchAndCacheStockAlphaVantage(symbol);
            return new StockDetailDto(
                    data.getSymbol(),
                    data.getName(),
                    data.getCurrentPrice(),
                    data.getChange24h(),
                    data.getPriceAsOf(),
                    data.isStale(),
                    data.getRecommendation(),
                    data.getLatestReportTitle(),
                    data.getLatestReportUrl()
            );
        }
        CachedStockData data = fetchAndCacheStock(symbol, "1m");
        return new StockDetailDto(
                data.getSymbol(),
                data.getName(),
                data.getCurrentPrice(),
                data.getChange24h(),
                data.getPriceAsOf(),
                data.isStale(),
                data.getRecommendation(),
                data.getLatestReportTitle(),
                data.getLatestReportUrl(),
                data.getMarketDataProvider(),
                data.getAiShard()
        );
    }

    private String generateRecommendationWithGemini(String symbol, String name, BigDecimal currentPrice, List<CandleDto> candles, List<NewsArticleDto> providerNews) {
        String apiKey = null;
        if (geminiShardRouter != null) {
            try {
                GeminiShardRouter.GeminiShardInfo shard = geminiShardRouter.resolveShard(symbol);
                apiKey = shard.apiKey();
            } catch (Exception e) {
                log.warn("Không thể xác định Gemini Shard cho mã {}: {}", symbol, e.getClass().getSimpleName());
                return null;
            }
        } else {
            apiKey = geminiApiKey;
        }

        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            log.debug("Gemini API key chưa cấu hình cho mã {}, bỏ qua phân tích khuyến nghị", symbol);
            return null;
        }

        try {
            String systemPrompt = """
                Bạn là Chuyên gia Tư vấn Đầu tư Cổ phiếu cấp cao của FNMF.
                Nhiệm vụ: Phân tích kỹ thuật nến và thông tin liên quan để đưa ra khuyến nghị cho nhà đầu tư cá nhân.
                QUY TẮC BẮT BUỘC:
                1. Khuyến nghị CHỈ ĐƯỢC CHỌN đúng 1 trong 2 nhãn:
                   - "Nên cân nhắc mua" (khi xu hướng tích cực, giá giữ trên vùng hỗ trợ, có tiềm năng tăng trưởng)
                   - "Chưa nên mua" (khi đang điều chỉnh, xu hướng giảm, rủi ro biến động cao hoặc thiếu động lực tăng)
                2. Tuyệt đối không dùng các nhãn khác.
                3. Tuyệt đối không tự tạo tin tức giả. Nếu không có mục tin tức thị trường được cung cấp, chỉ phân tích nến kỹ thuật.
                Định dạng JSON bắt buộc:
                {
                  "recommendation": "Nên cân nhắc mua" | "Chưa nên mua"
                }
                """;

            NewsAiCache latestNews = getLatestRealNews(symbol);
            String userPrompt = buildGeminiUserPrompt(symbol, name, currentPrice, candles, latestNews, providerNews);

            Map<String, Object> requestBody = Map.of(
                    "model", geminiModel != null ? geminiModel : "gemini-3.6-flash",
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            String payload = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(geminiApiUrl))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(12))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }

            String content = choices.get(0).path("message").path("content").asText("").trim();
            if (content.startsWith("```json")) content = content.substring(7);
            if (content.startsWith("```")) content = content.substring(3);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.trim();

            JsonNode parsed = objectMapper.readTree(content);
            String rec = parsed.path("recommendation").asText("").trim();
            if (REC_CONSIDER_BUY.equals(rec)) {
                return REC_CONSIDER_BUY;
            } else if (REC_AVOID_BUY.equals(rec)) {
                return REC_AVOID_BUY;
            }
            log.warn("Gemini trả nhãn khuyến nghị không hợp lệ cho mã {}: không khớp chính xác", symbol);
            return null;
        } catch (Exception e) {
            log.debug("Lỗi khi tạo khuyến nghị Gemini cho mã {}: {}", symbol, e.getClass().getSimpleName());
        }
        return null;
    }

    public String buildGeminiUserPrompt(String symbol, String name, BigDecimal currentPrice, List<CandleDto> candles, NewsAiCache latestNews) {
        return buildGeminiUserPrompt(symbol, name, currentPrice, candles, latestNews, Collections.emptyList());
    }

    public String buildGeminiUserPrompt(String symbol, String name, BigDecimal currentPrice, List<CandleDto> candles, NewsAiCache latestNews, List<NewsArticleDto> providerNews) {
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Cổ phiếu: ").append(symbol).append(" (").append(name).append(")\n");
        userPrompt.append("Giá hiện tại: $").append(currentPrice).append("\n");
        userPrompt.append("Dữ liệu ").append(candles.size()).append(" nến gần nhất:\n");
        int candleLimit = Math.min(10, candles.size());
        for (int i = candles.size() - candleLimit; i < candles.size(); i++) {
            CandleDto c = candles.get(i);
            userPrompt.append(String.format("Ngày %s: Đóng=%s, Cao=%s, Thấp=%s, Khối lượng=%s\n",
                    c.getTime(), c.getClose(), c.getHigh(), c.getLow(), c.getVolume()));
        }

        if (providerNews != null && !providerNews.isEmpty()) {
            userPrompt.append("\nThông tin tin tức từ nhà cung cấp:\n");
            for (NewsArticleDto n : providerNews) {
                userPrompt.append("- Tiêu đề: ").append(n.title()).append("\n");
                if (n.summary() != null && !n.summary().isBlank()) {
                    userPrompt.append("  Tóm tắt: ").append(n.summary()).append("\n");
                }
            }
        } else if (latestNews != null) {
            String viTitle = extractValidVietnameseTitle(latestNews);
            String viSummary = extractValidVietnameseSummary(latestNews);
            if (viTitle != null || viSummary != null) {
                userPrompt.append("\nThông tin tin tức thị trường thực tế:\n");
                if (viTitle != null) userPrompt.append("- Tiêu đề: ").append(viTitle).append("\n");
                if (viSummary != null) userPrompt.append("- Tóm tắt: ").append(viSummary).append("\n");
            }
        }

        return userPrompt.toString();
    }

    private void populateLatestReport(CachedStockData data, String symbol, List<NewsArticleDto> providerNews) {
        if (providerNews != null && !providerNews.isEmpty()) {
            for (NewsArticleDto n : providerNews) {
                if (n.url() != null && !n.url().isBlank()) {
                    data.setLatestReportTitle(n.title());
                    data.setLatestReportUrl(n.url());
                    return;
                }
            }
        }
        populateLatestReport(data, symbol);
    }

    private void populateLatestReport(CachedStockData data, String symbol) {
        NewsAiCache news = getLatestRealNews(symbol);
        if (news != null && news.getArticleUrl() != null && !news.getArticleUrl().isBlank()) {
            String title = (news.getDisplayTitleVi() != null && !news.getDisplayTitleVi().isBlank())
                    ? news.getDisplayTitleVi()
                    : news.getTitle();
            data.setLatestReportTitle(title);
            data.setLatestReportUrl(news.getArticleUrl());
        } else {
            data.setLatestReportTitle(null);
            data.setLatestReportUrl(null);
        }
    }

    NewsAiCache getLatestRealNews(String symbol) {
        try {
            List<NewsAiCache> list = newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(symbol);
            if (list != null && !list.isEmpty()) {
                for (NewsAiCache item : list) {
                    if (item.getArticleUrl() != null && !item.getArticleUrl().isBlank()) {
                        return item;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Lỗi khi tra cứu bài báo cho cổ phiếu {}: {}", symbol, e.getClass().getSimpleName());
        }
        return null;
    }

    String extractValidVietnameseTitle(NewsAiCache news) {
        if (news == null) return null;
        if (news.getDisplayTitleVi() != null && !news.getDisplayTitleVi().isBlank()
                && com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(news.getDisplayTitleVi())) {
            return news.getDisplayTitleVi().trim();
        }
        if (news.getTitle() != null && !news.getTitle().isBlank()
                && com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(news.getTitle())) {
            return news.getTitle().trim();
        }
        return null;
    }

    String extractValidVietnameseSummary(NewsAiCache news) {
        if (news == null) return null;
        if (news.getDisplaySummaryVi() != null && !news.getDisplaySummaryVi().isBlank()
                && com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(news.getDisplaySummaryVi())) {
            return news.getDisplaySummaryVi().trim();
        }
        if (news.getOriginalSummary() != null && !news.getOriginalSummary().isBlank()
                && com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(news.getOriginalSummary())) {
            return news.getOriginalSummary().trim();
        }
        return null;
    }

    // =========================================================================
    // TESTING HELPERS & LEGACY PARSING COMPATIBILITY
    // =========================================================================

    public void clearCache() {
        stockCache.clear();
        stockIntervalCache.clear();
        symbolCooldownMap.clear();
        geminiAttemptTimestampMap.clear();
        if (fixedMarketCacheManager != null) {
            fixedMarketCacheManager.clearAll();
        }
    }

    public void putInCache(CachedStockData data) {
        stockCache.put(data.getSymbol(), data);
    }

    public Long getCooldownUntil(String symbol) {
        return symbolCooldownMap.get(MarketSymbolConfig.getCanonicalSymbol(symbol));
    }

    public void setCooldownUntil(String symbol, long until) {
        symbolCooldownMap.put(MarketSymbolConfig.getCanonicalSymbol(symbol), until);
    }

    public CandleDto validateAndBuildCandle(String dateStr, JsonNode dayNode) {
        if (dayNode == null || !dayNode.isObject()) {
            throw new IllegalArgumentException("Dữ liệu nến không hợp lệ tại ngày: " + dateStr);
        }
        if (!dayNode.hasNonNull("1. open") || !dayNode.hasNonNull("2. high") ||
                !dayNode.hasNonNull("3. low") || !dayNode.hasNonNull("4. close") ||
                !dayNode.hasNonNull("5. volume")) {
            throw new IllegalArgumentException("Thiếu trường OHLCV tại ngày: " + dateStr);
        }

        BigDecimal open = parseDecimal(dayNode.path("1. open").asText());
        BigDecimal high = parseDecimal(dayNode.path("2. high").asText());
        BigDecimal low = parseDecimal(dayNode.path("3. low").asText());
        BigDecimal close = parseDecimal(dayNode.path("4. close").asText());
        BigDecimal volume = parseDecimal(dayNode.path("5. volume").asText());

        if (open.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(BigDecimal.ZERO) <= 0 ||
                low.compareTo(BigDecimal.ZERO) <= 0 || close.compareTo(BigDecimal.ZERO) <= 0 ||
                volume.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Giá hoặc khối lượng nến không hợp lệ (<= 0) tại ngày: " + dateStr);
        }

        if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0 ||
                low.compareTo(open) > 0 || low.compareTo(close) > 0) {
            throw new IllegalArgumentException("High/Low không hợp lệ tại ngày: " + dateStr);
        }

        return new CandleDto(dateStr, open, high, low, close, volume);
    }

    public BigDecimal parseDecimal(String val) {
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Dữ liệu số không được để trống");
        }
        try {
            return new BigDecimal(val.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Dữ liệu số không hợp lệ: " + val);
        }
    }

    // =========================================================================
    // INNER CACHE DATA MODEL
    // =========================================================================

    public static class CachedStockData {
        private final String symbol;
        private final String name;
        private final BigDecimal currentPrice;
        private final BigDecimal change24h;
        private final String priceAsOf;
        private final List<CandleDto> candles;
        private final long fetchedTimestamp;
        private boolean stale;
        private String recommendation;
        private long recommendationTimestamp;
        private String latestReportTitle;
        private String latestReportUrl;
        private String marketDataProvider;
        private String aiShard;
        private Integer candleCount;

        public CachedStockData(String symbol, String name, BigDecimal currentPrice, BigDecimal change24h,
                               String priceAsOf, List<CandleDto> candles, long fetchedTimestamp, boolean stale) {
            this.symbol = symbol;
            this.name = name;
            this.currentPrice = currentPrice;
            this.change24h = change24h;
            this.priceAsOf = priceAsOf;
            this.candles = candles;
            this.fetchedTimestamp = fetchedTimestamp;
            this.stale = stale;
        }

        public String getSymbol() { return symbol; }
        public String getName() { return name; }
        public BigDecimal getCurrentPrice() { return currentPrice; }
        public BigDecimal getChange24h() { return change24h; }
        public String getPriceAsOf() { return priceAsOf; }
        public List<CandleDto> getCandles() { return candles; }
        public long getFetchedTimestamp() { return fetchedTimestamp; }
        public boolean isStale() { return stale; }
        public void setStale(boolean stale) { this.stale = stale; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
        public long getRecommendationTimestamp() { return recommendationTimestamp; }
        public void setRecommendationTimestamp(long recommendationTimestamp) { this.recommendationTimestamp = recommendationTimestamp; }
        public String getLatestReportTitle() { return latestReportTitle; }
        public void setLatestReportTitle(String latestReportTitle) { this.latestReportTitle = latestReportTitle; }
        public String getLatestReportUrl() { return latestReportUrl; }
        public void setLatestReportUrl(String latestReportUrl) { this.latestReportUrl = latestReportUrl; }
        public String getMarketDataProvider() { return marketDataProvider; }
        public void setMarketDataProvider(String marketDataProvider) { this.marketDataProvider = marketDataProvider; }
        public String getAiShard() { return aiShard; }
        public void setAiShard(String aiShard) { this.aiShard = aiShard; }
        public Integer getCandleCount() { return candleCount; }
        public void setCandleCount(Integer candleCount) { this.candleCount = candleCount; }
    }
}
