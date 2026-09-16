package com.llmgateway.service;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.market.NewsArticleDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.service.provider.FixedMarketCacheManager;
import com.llmgateway.service.provider.FixedMarketProviderRouter;
import com.llmgateway.service.provider.MarketContentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final MarketForecastRepository forecastRepository;
    private final NewsAiCacheRepository newsAiCacheRepository;
    private final MarketDataService marketDataService;
    private final ForecastCacheService forecastCacheService;
    private final GeminiForecastClient geminiForecastClient;
    private final FixedMarketProviderRouter fixedMarketProviderRouter;
    private final FixedMarketCacheManager fixedMarketCacheManager;

    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(forecastRepository,
             newsAiCacheRepository,
             marketDataService,
             new ForecastCacheService(forecastRepository, objectMapper),
             new GeminiForecastClient(objectMapper),
             null,
             null);
    }

    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           ForecastCacheService forecastCacheService,
                           GeminiForecastClient geminiForecastClient) {
        this(forecastRepository,
             newsAiCacheRepository,
             marketDataService,
             forecastCacheService,
             geminiForecastClient,
             null,
             null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           ForecastCacheService forecastCacheService,
                           GeminiForecastClient geminiForecastClient,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) FixedMarketProviderRouter fixedMarketProviderRouter,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) FixedMarketCacheManager fixedMarketCacheManager) {
        this.forecastRepository = forecastRepository;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.marketDataService = marketDataService;
        this.forecastCacheService = forecastCacheService;
        this.geminiForecastClient = geminiForecastClient;
        this.fixedMarketProviderRouter = fixedMarketProviderRouter;
        this.fixedMarketCacheManager = fixedMarketCacheManager;
    }

    /**
     * Tạo hoặc lấy bản nhận định toàn thị trường AI thống nhất (MARKET-WIDE FORECAST).
     * 1. Cache key duy nhất: MARKET.
     * 2. Phân tích tổng hợp từ toàn bộ 8 tài sản Binance + nến thực tế + tin tức thị trường chung.
     * 3. Chỉ gọi Gemini khi chưa có cache hợp lệ (15 phút) hoặc pull-to-refresh (bypassCache = true).
     * 4. Khi Gemini lỗi (hết quota, timeout, network):
     *    - Trả về bản dự báo MARKET thành công gần nhất từ CSDL với stale = true, fromCache = true.
     *    - Nếu chưa từng có bản dự báo nào trong CSDL: ném ForecastUnavailableException (503).
     */
    public ForecastResponse generateMarketForecast(String timeframe, boolean bypassCache) {
        String tf = (timeframe != null && !timeframe.isBlank()) ? timeframe : "24H_7D";

        // 1. Kiểm tra CSDL cache (chỉ chấp nhận nguồn GEMINI)
        if (!bypassCache) {
            try {
                MarketPriceDto benchmarkPriceDto = resolveMarketBenchmarkPrice();
                Optional<ForecastResponse> cached = forecastCacheService.getFreshForecast("MARKET", benchmarkPriceDto);
                if (cached.isPresent()) {
                    ForecastResponse resp = cached.get();
                    resp.setFromCache(true);
                    resp.setStale(false);
                    return resp;
                }
            } catch (Exception e) {
                log.warn("Không thể kiểm tra cache với giá benchmark: {}", e.getClass().getSimpleName());
            }
        }

        // 2. Thu thập dữ liệu toàn bộ 8 tài sản Binance + nến BTC + tin tức vĩ mô thị trường
        Exception lastException = null;
        try {
            resolveMarketBenchmarkPrice();
            List<MarketPriceDto> allPrices = marketDataService.getAllPrices();
            List<CandleDto> candles = marketDataService.getCandles("BTCUSDT", "daily");
            List<NewsAiCache> recentNews = fetchMarketNews();

            // 3. Phân tích qua Gemini AI với dữ liệu thực tế
            ForecastResponse response = geminiForecastClient.requestMarketForecast(
                    allPrices,
                    candles,
                    recentNews,
                    tf
            );

            if (response != null) {
                // 4. Lưu bản dự báo vào CSDL
                forecastCacheService.saveForecast(response);
                response.setFromCache(false);
                response.setStale(false);
                return response;
            }
        } catch (Exception e) {
            lastException = e;
            log.warn("Lỗi khi tạo nhận định toàn thị trường từ Gemini: {}", e.getClass().getSimpleName());
        }

        // 5. Fallback khi Gemini lỗi hoặc không có giá BTC thật:
        // Tìm bản ghi MARKET nguồn GEMINI hợp lệ gần nhất trong CSDL (không bỏ cuộc nếu bản mới nhất sai nguồn)
        List<MarketForecast> records = forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        if (records != null) {
            for (MarketForecast record : records) {
                if ("GEMINI".equalsIgnoreCase(record.getAnalysisSource())) {
                    List<String> keyDrivers = forecastCacheService.parseKeyDrivers(record.getAnalysisSummary());
                    ForecastResponse fallback = new ForecastResponse(
                            "MARKET",
                            "Nhận định toàn thị trường",
                            record.getCurrentPrice(),
                            record.getTrendPrediction(),
                            record.getTimeframe(),
                            record.getSupportLevel(),
                            record.getResistanceLevel(),
                            record.getRecommendation(),
                            record.getConfidenceScore() != null ? record.getConfidenceScore().intValue() : 50,
                            keyDrivers,
                            record.getTechnicalOutlook(),
                            record.getFundamentalOutlook(),
                            "GEMINI",
                            record.getCandleCount() != null ? record.getCandleCount() : 30,
                            true,
                            record.getCreatedAt()
                    );
                    fallback.setAiShard(record.getAiShard());
                    fallback.setStale(true);
                    if (ForecastQualityPolicy.isValid(fallback)) {
                        return fallback;
                    } else {
                        log.warn("Bản ghi stale MARKET nguồn GEMINI không thỏa mãn chính sách chất lượng");
                    }
                } else {
                    log.warn("Bỏ qua bản ghi stale MARKET không phải nguồn GEMINI: {}", record.getAnalysisSource());
                }
            }
        }

        // 6. Nếu không có giá thật và không có cache GEMINI hợp lệ: trả lỗi 503 an toàn cố định
        throw new ForecastUnavailableException(
                "Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.",
                lastException);
    }

    public ForecastResponse generateForecast(ForecastRequest request) {
        return generateForecast(request, false);
    }

    public ForecastResponse generateForecast(ForecastRequest request, boolean bypassCache) {
        String timeframe = (request != null && request.getTimeframe() != null) ? request.getTimeframe() : "24H_7D";
        return generateMarketForecast(timeframe, bypassCache);
    }

    public List<NewsAiCache> fetchMarketNews() {
        List<NewsAiCache> result = new ArrayList<>();
        if (newsAiCacheRepository != null) {
            try {
                List<NewsAiCache> all = newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc();
                if (all != null) {
                    for (NewsAiCache item : all) {
                        result.add(item);
                        if (result.size() >= 5) break;
                    }
                }
            } catch (Exception e) {
                log.debug("Lỗi tra cứu tin tức thị trường: {}", e.getClass().getSimpleName());
            }
        }
        return result;
    }

    public MarketPriceDto resolveMarketBenchmarkPrice() {
        try {
            MarketPriceDto btc = marketDataService.getPriceBySymbol("BTCUSDT");
            if (btc != null && !btc.isStale() && btc.getPrice() != null && btc.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                return new MarketPriceDto(
                        "MARKET",
                        "Nhận định toàn thị trường",
                        "MARKET",
                        btc.getPrice(),
                        btc.getChange24h() != null ? btc.getChange24h() : BigDecimal.ZERO,
                        btc.getBidPrice(),
                        btc.getAskPrice(),
                        btc.getLastUpdated(),
                        false,
                        "BINANCE",
                        btc.getPriceAsOf()
                );
            }
        } catch (Exception e) {
            log.warn("Không thể lấy giá BTCUSDT làm benchmark thị trường: {}", e.getClass().getSimpleName());
        }
        throw new MarketDataUnavailableException("Không có dữ liệu giá BTC thực tế để làm mốc tham chiếu nhận định");
    }

    /**
     * Thu thập tối đa 3 tin tức CHÍNH XÁC của symbol được yêu cầu.
     * Cấm dùng findTop10ByOrderByPublishedAtDesc() toàn bộ sàn.
     * Cấm dùng tin tức của symbol khác.
     */
    public List<NewsAiCache> fetchNewsForSymbol(String cleanSymbol) {
        if (cleanSymbol == null || cleanSymbol.isBlank()) {
            return Collections.emptyList();
        }

        String canonical = MarketSymbolConfig.isSupported(cleanSymbol)
                ? MarketSymbolConfig.getCanonicalSymbol(cleanSymbol)
                : cleanSymbol.trim().toUpperCase();
        List<NewsAiCache> result = new ArrayList<>();

        if (MarketSymbolConfig.isSupported(cleanSymbol) && MarketSymbolConfig.isStock(canonical)) {
            // Cổ phiếu: tra cứu qua FixedMarketCacheManager / FixedMarketProviderRouter
            if (fixedMarketProviderRouter != null) {
                try {
                    MarketContentProvider provider = fixedMarketProviderRouter.getProvider(canonical);
                    String providerName = provider.providerName();
                    List<NewsArticleDto> providerNews = null;

                    if (fixedMarketCacheManager != null) {
                        FixedMarketCacheManager.CachedEntry<List<NewsArticleDto>> cachedNews = fixedMarketCacheManager.getNewsEntry(providerName, canonical);
                        if (cachedNews != null && cachedNews.isFresh(FixedMarketCacheManager.NEWS_TTL_MS)) {
                            providerNews = cachedNews.getData();
                        }
                    }

                    if (providerNews == null) {
                        providerNews = provider.getLatestNews(canonical, 3);
                        if (providerNews == null) {
                            providerNews = Collections.emptyList();
                        }
                        if (fixedMarketCacheManager != null) {
                            fixedMarketCacheManager.putNews(providerName, canonical, providerNews);
                        }
                    }

                    if (providerNews != null && !providerNews.isEmpty()) {
                        for (NewsArticleDto art : providerNews) {
                            if (result.size() >= 3) break;
                            NewsAiCache item = new NewsAiCache();
                            item.setSymbol(canonical);
                            item.setTitle(art.title());
                            item.setOriginalSummary(art.summary());
                            item.setSentiment("NEUTRAL");
                            item.setReason("Tin tức từ " + providerName);
                            item.setArticleUrl(art.url());
                            result.add(item);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Không thể lấy tin tức từ provider cho cổ phiếu {}: {}", canonical, e.getClass().getSimpleName());
                }
            }

            // Fallback tra cứu DB riêng cho canonical symbol nếu có
            if (result.isEmpty() && newsAiCacheRepository != null) {
                try {
                    List<NewsAiCache> dbNews = newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(canonical);
                    if (dbNews != null) {
                        for (NewsAiCache item : dbNews) {
                            if (canonical.equalsIgnoreCase(item.getSymbol())) {
                                result.add(item);
                                if (result.size() >= 3) break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Lỗi tra cứu tin tức CSDL cho {}: {}", canonical, e.getClass().getSimpleName());
                }
            }
        } else {
            // Crypto: tra cứu trong newsAiCacheRepository đúng cho canonical symbol
            if (newsAiCacheRepository != null) {
                try {
                    List<NewsAiCache> dbNews = newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(canonical);
                    if (dbNews != null) {
                        for (NewsAiCache item : dbNews) {
                            if (canonical.equalsIgnoreCase(item.getSymbol())) {
                                result.add(item);
                                if (result.size() >= 3) break;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Lỗi tra cứu tin tức crypto cho {}: {}", canonical, e.getClass().getSimpleName());
                }
            }
        }

        return result;
    }

    public List<MarketForecast> getForecastHistory(String symbol) {
        if (symbol == null || symbol.isBlank() || "MARKET".equalsIgnoreCase(symbol.trim())) {
            return forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        }
        String cleanSymbol = symbol.trim().toUpperCase();
        List<MarketForecast> history = forecastRepository.findBySymbolOrderByCreatedAtDesc(cleanSymbol);
        if (history.isEmpty()) {
            return forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        }
        return history;
    }

    public List<MarketForecast> getLatestForecasts() {
        return forecastRepository.findTop10ByOrderByCreatedAtDesc();
    }

    /**
     * Lấy bản dự báo từ cache CSDL mà không gọi Gemini Provider hay Market Data Provider (dùng cho Replay).
     * Tuyệt đối không gọi resolveMarketBenchmarkPrice() hoặc bất kỳ provider nào.
     * Đọc trực tiếp bản MARKET mới nhất trong CSDL, chỉ chấp nhận analysisSource=GEMINI và ForecastQualityPolicy hợp lệ.
     */
    public Optional<ForecastResponse> getFreshForecastFromCacheOnly(String symbol) {
        List<MarketForecast> records = forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET");
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }

        for (MarketForecast record : records) {
            if ("GEMINI".equalsIgnoreCase(record.getAnalysisSource())) {
                List<String> keyDrivers = forecastCacheService.parseKeyDrivers(record.getAnalysisSummary());
                ForecastResponse resp = new ForecastResponse(
                        "MARKET",
                        "Nhận định toàn thị trường",
                        record.getCurrentPrice(),
                        record.getTrendPrediction(),
                        record.getTimeframe(),
                        record.getSupportLevel(),
                        record.getResistanceLevel(),
                        record.getRecommendation(),
                        record.getConfidenceScore() != null ? record.getConfidenceScore().intValue() : null,
                        keyDrivers,
                        record.getTechnicalOutlook(),
                        record.getFundamentalOutlook(),
                        "GEMINI",
                        record.getCandleCount(),
                        true,
                        record.getCreatedAt()
                );
                resp.setAiShard(record.getAiShard());
                resp.setFromCache(true);
                resp.setStale(false);
                if (ForecastQualityPolicy.isValid(resp)) {
                    return Optional.of(resp);
                }
            }
        }

        return Optional.empty();
    }
}
