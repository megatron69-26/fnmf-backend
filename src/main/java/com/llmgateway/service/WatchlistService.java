package com.llmgateway.service;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.watchlist.WatchlistAiInsightDto;
import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.dto.watchlist.WatchlistRequest;
import com.llmgateway.entity.Watchlist;
import com.llmgateway.repository.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistRepository watchlistRepository;
    private final MarketDataService marketDataService;
    private final ForecastService forecastService;
    private final AiNewsService aiNewsService;
    private final WatchlistTxWriter watchlistTxWriter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StockMarketService stockMarketService;

    public WatchlistService(WatchlistRepository watchlistRepository,
                            MarketDataService marketDataService,
                            ForecastService forecastService,
                            AiNewsService aiNewsService) {
        this(watchlistRepository, marketDataService, forecastService, aiNewsService, null, new WatchlistTxWriter(watchlistRepository));
    }

    public WatchlistService(WatchlistRepository watchlistRepository,
                            MarketDataService marketDataService,
                            ForecastService forecastService,
                            AiNewsService aiNewsService,
                            StockMarketService stockMarketService) {
        this(watchlistRepository, marketDataService, forecastService, aiNewsService, stockMarketService, new WatchlistTxWriter(watchlistRepository));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WatchlistService(WatchlistRepository watchlistRepository,
                            MarketDataService marketDataService,
                            ForecastService forecastService,
                            AiNewsService aiNewsService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) StockMarketService stockMarketService,
                            WatchlistTxWriter watchlistTxWriter) {
        this.watchlistRepository = watchlistRepository;
        this.marketDataService = marketDataService;
        this.forecastService = forecastService;
        this.aiNewsService = aiNewsService;
        this.stockMarketService = stockMarketService;
        this.watchlistTxWriter = watchlistTxWriter != null ? watchlistTxWriter : new WatchlistTxWriter(watchlistRepository);
    }

    public void setStockMarketService(StockMarketService stockMarketService) {
        this.stockMarketService = stockMarketService;
    }

    /**
     * Lấy danh sách watchlist của User từ PostgreSQL.
     * ĐƯỜNG ĐỌC NHANH (FAST READ PATH):
     * 1. Tuyệt đối KHÔNG phát sinh external HTTP request nào tới Binance, Alpha Vantage, Gemini hoặc provider khác.
     * 2. Chỉ trả các mã đang được phép giao dịch (8 mã Binance: BTCUSDT, ETHUSDT, XAUUSD, BNBUSDT, SOLUSDT, XRPUSDT, ADAUSDT, DOGEUSDT).
     * 3. Các mã cổ phiếu legacy (AAPL, MSFT, ...) được giữ trong DB nhưng ẩn khỏi active trading watchlist.
     * 4. Deduplicate: API response distinct theo canonical symbol.
     * 5. Đọc giá từ cache nội bộ; nếu không có cache thì currentPrice/change24h để null (không trả 0.0 giả).
     */
    @Transactional(readOnly = true)
    public List<WatchlistItemDto> getUserWatchlist(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistItemDto> result = new ArrayList<>();
        Set<String> seenSymbols = new HashSet<>();

        for (Watchlist w : items) {
            if (w.getSymbol() == null || w.getSymbol().isBlank()) {
                continue;
            }
            String rawSymbol = w.getSymbol().trim();

            // Chỉ xử lý các mã đang được phép giao dịch
            if (!MarketSymbolConfig.isSupported(rawSymbol)) {
                // Mã legacy hoặc không hỗ trợ: giữ nguyên trong DB nhưng ẩn khỏi active trading watchlist
                continue;
            }

            String canonical = MarketSymbolConfig.getCanonicalSymbol(rawSymbol);

            // Deduplicate: Mỗi canonical symbol chỉ xuất hiện đúng 1 lần
            if (!seenSymbols.add(canonical)) {
                continue;
            }

            // Đọc giá từ cache nội bộ (zero external provider calls)
            MarketPriceDto cachedPrice = null;
            try {
                cachedPrice = marketDataService.getCachedPrice(canonical);
            } catch (Exception e) {
                log.warn("Lỗi khi đọc cache giá cho symbol={}: {}", canonical, e.getClass().getSimpleName());
            }

            String displayName = MarketSymbolConfig.getDisplayName(canonical);
            String category = "CRYPTO";
            try {
                category = MarketSymbolConfig.getMeta(canonical).category();
            } catch (Exception ignored) {
            }

            result.add(new WatchlistItemDto(
                    w.getId(),
                    canonical,
                    (cachedPrice != null && cachedPrice.getName() != null) ? cachedPrice.getName() : displayName,
                    (cachedPrice != null && cachedPrice.getCategory() != null) ? cachedPrice.getCategory() : category,
                    cachedPrice != null ? cachedPrice.getPrice() : null,
                    cachedPrice != null ? cachedPrice.getChange24h() : null,
                    w.getDisplayOrder(),
                    w.getCreatedAt(),
                    cachedPrice != null ? cachedPrice.getPriceAsOf() : null,
                    cachedPrice != null && Boolean.TRUE.equals(cachedPrice.getStale()),
                    null,
                    null,
                    null
            ));
        }

        return result;
    }

    /**
     * Bản tin AI chuyên sâu cho các mã user đã tích quan tâm trong Watchlist.
     */
    public List<WatchlistAiInsightDto> getWatchlistAiInsights(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistAiInsightDto> insights = new ArrayList<>();
        Set<String> seenSymbols = new HashSet<>();

        for (Watchlist w : items) {
            if (w.getSymbol() == null || w.getSymbol().isBlank()) continue;
            String rawSymbol = w.getSymbol().trim();
            if (!MarketSymbolConfig.isSupported(rawSymbol)) continue;

            String canonical = MarketSymbolConfig.getCanonicalSymbol(rawSymbol);
            if (!seenSymbols.add(canonical)) continue;

            MarketPriceDto priceDto = null;
            try {
                priceDto = marketDataService.getCachedPrice(canonical);
            } catch (Exception e) {
                log.warn("Không thể tải giá cho insight symbol {}: {}", canonical, e.getClass().getSimpleName());
            }

            ForecastResponse forecast = null;
            try {
                ForecastRequest forecastReq = new ForecastRequest(canonical, "24H_7D");
                forecast = forecastService.generateForecast(forecastReq);
            } catch (Exception e) {
                log.warn("Không thể tạo forecast cho insight symbol {}: {}", canonical, e.getClass().getSimpleName());
            }

            List<NewsFeedItemDto> newsList = emptyNewsList();
            try {
                newsList = aiNewsService.getLiveAiNewsFeed(canonical, 2);
            } catch (Exception e) {
                log.warn("Không thể tải news cho insight symbol {}: {}", canonical, e.getClass().getSimpleName());
            }

            String displayName = MarketSymbolConfig.getDisplayName(canonical);
            String category = "CRYPTO";
            try {
                category = MarketSymbolConfig.getMeta(canonical).category();
            } catch (Exception ignored) {
            }

            insights.add(new WatchlistAiInsightDto(
                    w.getId(),
                    canonical,
                    priceDto != null && priceDto.getName() != null ? priceDto.getName() : displayName,
                    priceDto != null && priceDto.getCategory() != null ? priceDto.getCategory() : category,
                    priceDto != null ? priceDto.getPrice() : null,
                    priceDto != null ? priceDto.getChange24h() : null,
                    forecast,
                    newsList
            ));
        }

        return insights;
    }

    private List<NewsFeedItemDto> emptyNewsList() {
        return new ArrayList<>();
    }

    /**
     * Thêm một mã tài sản vào danh sách theo dõi.
     * Idempotent & Concurrency-safe:
     * - Nếu mã đã tồn tại, trả lại bản ghi hiện có an toàn thay vì gây 400/500.
     * - Hai request đồng thời thêm cùng mã không tạo duplicate nhờ unique constraint và catch DataIntegrityViolationException.
     * - Tách thao tác insert sang transaction độc lập (REQUIRES_NEW) qua WatchlistTxWriter để tránh làm hỏng transaction với rollback-only.
     * - Không phát sinh external network calls (đọc giá từ cache nội bộ).
     */
    public WatchlistItemDto addToWatchlist(Long userId, WatchlistRequest request) {
        MarketSymbolConfig.validateSupported(request.getSymbol());
        String cleanSymbol = MarketSymbolConfig.getCanonicalSymbol(request.getSymbol());

        Optional<Watchlist> existing = watchlistRepository.findByUserIdAndSymbol(userId, cleanSymbol);
        Watchlist watchlist;
        if (existing.isPresent()) {
            watchlist = existing.get();
            log.info("WATCHLIST ITEM ĐÃ TỒN TẠI (IDEMPOTENT) | userId={} | symbol={}", userId, cleanSymbol);
        } else {
            try {
                watchlist = watchlistTxWriter.insertInNewTx(userId, cleanSymbol, request.getDisplayOrder());
                log.info("THÊM WATCHLIST THÀNH CÔNG | userId={} | symbol={}", userId, cleanSymbol);
            } catch (DataIntegrityViolationException e) {
                // Xử lý race condition khi hai request đồng thời thêm cùng mã
                log.warn("Race condition khi thêm watchlist: class={} | symbol={}", e.getClass().getSimpleName(), cleanSymbol);
                watchlist = watchlistRepository.findByUserIdAndSymbol(userId, cleanSymbol)
                        .orElseThrow(() -> e);
            }
        }

        MarketPriceDto cachedPrice = marketDataService.getCachedPrice(cleanSymbol);
        String name = MarketSymbolConfig.getDisplayName(cleanSymbol);
        String category = "CRYPTO";
        try {
            category = MarketSymbolConfig.getMeta(cleanSymbol).category();
        } catch (Exception ignored) {
        }

        return new WatchlistItemDto(
                watchlist.getId(),
                cleanSymbol,
                (cachedPrice != null && cachedPrice.getName() != null) ? cachedPrice.getName() : name,
                (cachedPrice != null && cachedPrice.getCategory() != null) ? cachedPrice.getCategory() : category,
                cachedPrice != null ? cachedPrice.getPrice() : null,
                cachedPrice != null ? cachedPrice.getChange24h() : null,
                watchlist.getDisplayOrder(),
                watchlist.getCreatedAt(),
                cachedPrice != null ? cachedPrice.getPriceAsOf() : null,
                cachedPrice != null && Boolean.TRUE.equals(cachedPrice.getStale()),
                null,
                null,
                null
        );
    }

    /**
     * Xóa một mã khỏi danh sách theo dõi.
     */
    @Transactional
    public void removeFromWatchlist(Long userId, String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        try {
            String cleanSymbol = MarketSymbolConfig.getCanonicalSymbol(symbol);
            watchlistRepository.deleteByUserIdAndSymbol(userId, cleanSymbol);
            log.info("XÓA WATCHLIST THÀNH CÔNG | userId={} | symbol={}", userId, cleanSymbol);
        } catch (Exception e) {
            log.warn("Lỗi khi xóa watchlist: class={} | symbol={}", e.getClass().getSimpleName(), symbol);
        }
    }
}
