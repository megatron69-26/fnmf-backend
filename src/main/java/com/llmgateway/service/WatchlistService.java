package com.llmgateway.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    private final WatchlistRepository watchlistRepository;
    private final MarketDataService marketDataService;
    private final ForecastService forecastService;
    private final AiNewsService aiNewsService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StockMarketService stockMarketService;

    public WatchlistService(WatchlistRepository watchlistRepository,
                            MarketDataService marketDataService,
                            ForecastService forecastService,
                            AiNewsService aiNewsService) {
        this(watchlistRepository, marketDataService, forecastService, aiNewsService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WatchlistService(WatchlistRepository watchlistRepository,
                            MarketDataService marketDataService,
                            ForecastService forecastService,
                            AiNewsService aiNewsService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) StockMarketService stockMarketService) {
        this.watchlistRepository = watchlistRepository;
        this.marketDataService = marketDataService;
        this.forecastService = forecastService;
        this.aiNewsService = aiNewsService;
        this.stockMarketService = stockMarketService;
    }

    public void setStockMarketService(StockMarketService stockMarketService) {
        this.stockMarketService = stockMarketService;
    }

    /**
     * Lấy danh sách watchlist của User từ PostgreSQL.
     * Đối với cổ phiếu: Đọc từ cache sẵn có, không tự động gọi hàng loạt request ra ngoài.
     * Nếu một mã cổ phiếu chưa có trong cache, trả thông tin cơ bản kèm giá/khuyến nghị/bài báo null,
     * không làm hỏng toàn bộ danh sách.
     * Khi provider giá lỗi, price và change24h trả null (tuyệt đối không trả 0.0 giả).
     */
    public List<WatchlistItemDto> getUserWatchlist(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistItemDto> result = new ArrayList<>();

        for (Watchlist w : items) {
            String symbol = w.getSymbol();
            if (com.llmgateway.config.MarketSymbolConfig.isStock(symbol)) {
                StockMarketService.CachedStockData cached = (stockMarketService != null)
                        ? stockMarketService.getCachedStock(symbol)
                        : null;
                String name = com.llmgateway.config.MarketSymbolConfig.isSupported(symbol)
                        ? com.llmgateway.config.MarketSymbolConfig.getDisplayName(symbol)
                        : symbol;
                if (cached != null) {
                    result.add(new WatchlistItemDto(
                            w.getId(),
                            cached.getSymbol(),
                            cached.getName(),
                            "STOCK",
                            cached.getCurrentPrice(),
                            cached.getChange24h(),
                            w.getDisplayOrder(),
                            w.getCreatedAt(),
                            cached.getPriceAsOf(),
                            cached.isStale(),
                            cached.getRecommendation(),
                            cached.getLatestReportTitle(),
                            cached.getLatestReportUrl()
                    ));
                } else {
                    result.add(new WatchlistItemDto(
                            w.getId(),
                            symbol,
                            name,
                            "STOCK",
                            null,
                            null,
                            w.getDisplayOrder(),
                            w.getCreatedAt(),
                            null,
                            false,
                            null,
                            null,
                            null
                    ));
                }
            } else {
                MarketPriceDto priceDto = null;
                try {
                    priceDto = marketDataService.getPriceBySymbol(symbol);
                } catch (Exception e) {
                    log.warn("Không thể nạp giá cho mã trong watchlist: {} ({})", symbol, e.getMessage());
                }
                result.add(new WatchlistItemDto(
                        w.getId(),
                        symbol,
                        priceDto != null && priceDto.getName() != null ? priceDto.getName() : com.llmgateway.config.MarketSymbolConfig.getDisplayName(symbol),
                        priceDto != null && priceDto.getCategory() != null ? priceDto.getCategory() : "MARKET",
                        priceDto != null ? priceDto.getPrice() : null,
                        priceDto != null ? priceDto.getChange24h() : null,
                        w.getDisplayOrder(),
                        w.getCreatedAt(),
                        priceDto != null ? priceDto.getPriceAsOf() : null,
                        priceDto != null && Boolean.TRUE.equals(priceDto.getStale()),
                        null,
                        null,
                        null
                ));
            }
        }

        return result;
    }

    /**
     * Bản tin AI chuyên sâu cho các mã user đã tích quan tâm trong Watchlist.
     */
    public List<WatchlistAiInsightDto> getWatchlistAiInsights(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistAiInsightDto> insights = new ArrayList<>();

        for (Watchlist w : items) {
            String symbol = w.getSymbol();
            MarketPriceDto priceDto = null;
            try {
                priceDto = marketDataService.getPriceBySymbol(symbol);
            } catch (Exception e) {
                log.warn("Không thể tải giá cho insight symbol {}: {}", symbol, e.getMessage());
            }

            ForecastResponse forecast = null;
            try {
                ForecastRequest forecastReq = new ForecastRequest(symbol, "24H_7D");
                forecast = forecastService.generateForecast(forecastReq);
            } catch (Exception e) {
                log.warn("Không thể tạo forecast cho insight symbol {}: {}", symbol, e.getMessage());
            }

            List<NewsFeedItemDto> newsList = emptyNewsList();
            try {
                newsList = aiNewsService.getLiveAiNewsFeed(symbol, 2);
            } catch (Exception e) {
                log.warn("Không thể tải news cho insight symbol {}: {}", symbol, e.getMessage());
            }

            insights.add(new WatchlistAiInsightDto(
                    w.getId(),
                    symbol,
                    priceDto != null && priceDto.getName() != null ? priceDto.getName() : symbol,
                    priceDto != null && priceDto.getCategory() != null ? priceDto.getCategory() : "MARKET",
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
     * Khi người dùng bấm Quan tâm một mã cổ phiếu, hệ thống nạp dữ liệu cho mã đó và đưa vào cache.
     * Vẫn lưu thành công dù giá thời gian thực tạm thời lỗi.
     */
    @Transactional
    public WatchlistItemDto addToWatchlist(Long userId, WatchlistRequest request) {
        com.llmgateway.config.MarketSymbolConfig.validateSupported(request.getSymbol());
        String cleanSymbol = com.llmgateway.config.MarketSymbolConfig.getCanonicalSymbol(request.getSymbol());

        Optional<Watchlist> existing = watchlistRepository.findByUserIdAndSymbol(userId, cleanSymbol);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Mã tài sản " + cleanSymbol + " đã có trong danh sách theo dõi!");
        }

        Watchlist watchlist = new Watchlist();
        watchlist.setUserId(userId);
        watchlist.setSymbol(cleanSymbol);
        watchlist.setDisplayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 1);
        watchlist.setCreatedAt(LocalDateTime.now());

        watchlistRepository.save(watchlist);
        log.info("THÊM WATCHLIST THÀNH CÔNG | userId={} | symbol={}", userId, cleanSymbol);

        if (com.llmgateway.config.MarketSymbolConfig.isStock(cleanSymbol)) {
            StockMarketService.CachedStockData stockData = null;
            if (stockMarketService != null) {
                try {
                    stockData = stockMarketService.fetchAndCacheStock(cleanSymbol);
                } catch (Exception e) {
                    log.warn("Không thể nạp dữ liệu cổ phiếu khi thêm watchlist cho {}: {}", cleanSymbol, e.getMessage());
                }
            }
            String name = (stockData != null) ? stockData.getName() : com.llmgateway.config.MarketSymbolConfig.getDisplayName(cleanSymbol);
            return new WatchlistItemDto(
                    watchlist.getId(),
                    cleanSymbol,
                    name,
                    "STOCK",
                    stockData != null ? stockData.getCurrentPrice() : null,
                    stockData != null ? stockData.getChange24h() : null,
                    watchlist.getDisplayOrder(),
                    watchlist.getCreatedAt(),
                    stockData != null ? stockData.getPriceAsOf() : null,
                    stockData != null && stockData.isStale(),
                    stockData != null ? stockData.getRecommendation() : null,
                    stockData != null ? stockData.getLatestReportTitle() : null,
                    stockData != null ? stockData.getLatestReportUrl() : null
            );
        }

        MarketPriceDto priceDto = null;
        try {
            priceDto = marketDataService.getPriceBySymbol(cleanSymbol);
        } catch (Exception e) {
            log.warn("Không thể nạp giá khi thêm watchlist cho {}: {}", cleanSymbol, e.getMessage());
        }

        return new WatchlistItemDto(
                watchlist.getId(),
                cleanSymbol,
                priceDto != null && priceDto.getName() != null ? priceDto.getName() : cleanSymbol,
                priceDto != null && priceDto.getCategory() != null ? priceDto.getCategory() : "MARKET",
                priceDto != null ? priceDto.getPrice() : null,
                priceDto != null ? priceDto.getChange24h() : null,
                watchlist.getDisplayOrder(),
                watchlist.getCreatedAt(),
                priceDto != null ? priceDto.getPriceAsOf() : null,
                priceDto != null && Boolean.TRUE.equals(priceDto.getStale()),
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
        String cleanSymbol = symbol.trim().toUpperCase();
        watchlistRepository.deleteByUserIdAndSymbol(userId, cleanSymbol);
        log.info("XÓA WATCHLIST THÀNH CÔNG | userId={} | symbol={}", userId, cleanSymbol);
    }
}
