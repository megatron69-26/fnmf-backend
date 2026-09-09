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

    public WatchlistService(WatchlistRepository watchlistRepository,
                            MarketDataService marketDataService,
                            ForecastService forecastService,
                            AiNewsService aiNewsService) {
        this.watchlistRepository = watchlistRepository;
        this.marketDataService = marketDataService;
        this.forecastService = forecastService;
        this.aiNewsService = aiNewsService;
    }

    /**
     * Lấy danh sách watchlist của User từ PostgreSQL.
     * Khi provider giá lỗi, price và change24h trả null (tuyệt đối không trả 0.0 giả).
     * Tất cả các mã đã lưu trong DB đều được trả về đầy đủ.
     */
    public List<WatchlistItemDto> getUserWatchlist(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistItemDto> result = new ArrayList<>();

        for (Watchlist w : items) {
            MarketPriceDto priceDto = null;
            try {
                priceDto = marketDataService.getPriceBySymbol(w.getSymbol());
            } catch (Exception e) {
                log.warn("Không thể nạp giá cho mã trong watchlist: {} ({})", w.getSymbol(), e.getMessage());
            }
            result.add(new WatchlistItemDto(
                    w.getId(),
                    w.getSymbol(),
                    priceDto != null && priceDto.getName() != null ? priceDto.getName() : w.getSymbol(),
                    priceDto != null && priceDto.getCategory() != null ? priceDto.getCategory() : "MARKET",
                    priceDto != null ? priceDto.getPrice() : null,
                    priceDto != null ? priceDto.getChange24h() : null,
                    w.getDisplayOrder(),
                    w.getCreatedAt()
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
                watchlist.getCreatedAt()
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
