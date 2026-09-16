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
     * Tạo hoặc lấy bản dự báo thị trường AI dựa trên:
     * 1. Kiểm tra giá thị trường thời gian thực (Zero Fake / Zero Stale).
     * 2. Kiểm tra bộ nhớ đệm CSDL (15 phút) - chỉ chấp nhận nguồn GEMINI.
     * 3. Thu thập nến thực tế (tối đa 30 cây nến) và tin tức vĩ mô theo đúng symbol.
     * 4. Gọi Gemini AI và kiểm duyệt nghiêm ngặt theo ForecastQualityPolicy.
     * 5. Lưu vào CSDL và trả về kết quả.
     * Khi có lỗi mạng/AI/thiếu nến/không đạt chuẩn: ném ForecastUnavailableException (HTTP 503),
     * tuyệt đối không sinh dữ liệu heuristic bịa đặt.
     */
    public ForecastResponse generateForecast(ForecastRequest request) {
        return generateForecast(request, false);
    }

    public ForecastResponse generateForecast(ForecastRequest request, boolean bypassCache) {
        if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Mã tài sản không được để trống");
        }

        String cleanSymbol = request.getSymbol().trim().toUpperCase();

        // 1. Kiểm tra tính khả dụng của giá thị trường thời gian thực
        MarketPriceDto priceDto = marketDataService.getPriceBySymbol(cleanSymbol);
        if (priceDto == null || Boolean.TRUE.equals(priceDto.isStale()) || priceDto.getPrice() == null) {
            throw new MarketDataUnavailableException("Dữ liệu thị trường thời gian thực không khả dụng hoặc bị cũ (stale), không thể tạo dự báo cho mã: " + cleanSymbol);
        }

        // 2. Kiểm tra CSDL xem có bản dự báo còn hạn từ nguồn GEMINI hay không (bỏ qua nếu bypassCache = true)
        if (!bypassCache) {
            Optional<ForecastResponse> cached = forecastCacheService.getFreshForecast(cleanSymbol, priceDto);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // 3. Thu thập dữ liệu nến thực tế từ sàn & tin tức theo đúng symbol (Blocker 5: Zero cross-symbol news)
        List<CandleDto> candles = marketDataService.getCandles(cleanSymbol, "daily");
        List<NewsAiCache> recentNews = fetchNewsForSymbol(cleanSymbol);

        // 4. Phân tích qua Gemini AI với dữ liệu nến thực tế
        ForecastResponse response = geminiForecastClient.requestForecast(
                cleanSymbol,
                priceDto,
                candles,
                recentNews,
                request.getTimeframe()
        );

        if (response == null) {
            throw new ForecastUnavailableException("Dịch vụ AI không phản hồi hoặc trả về kết quả rỗng");
        }

        // 5. Lưu bản dự báo vào CSDL
        forecastCacheService.saveForecast(response);

        response.setFromCache(false);
        return response;
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
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String cleanSymbol = symbol.trim().toUpperCase();
        return forecastRepository.findBySymbolOrderByCreatedAtDesc(cleanSymbol);
    }

    public List<MarketForecast> getLatestForecasts() {
        return forecastRepository.findTop10ByOrderByCreatedAtDesc();
    }

    /**
     * Lấy bản dự báo từ cache CSDL mà không gọi Gemini Provider (dùng cho Replay).
     */
    public Optional<ForecastResponse> getFreshForecastFromCacheOnly(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        String cleanSymbol = symbol.trim().toUpperCase();
        MarketPriceDto priceDto = null;
        try {
            priceDto = marketDataService.getPriceBySymbol(cleanSymbol);
        } catch (Exception ignored) {
        }
        return forecastCacheService.getFreshForecast(cleanSymbol, priceDto);
    }
}
