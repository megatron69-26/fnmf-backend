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

    // ====================================================================================
    // 🎓 [CÂU HỎI BẢO VỆ ĐỒ ÁN: TÍNH NĂNG WATCHLIST & BẢO MẬT DỮ LIỆU NGƯỜI DÙNG]
    // ------------------------------------------------------------------------------------
    // CÂU HỎI CỦA GIẢNG VIÊN:
    //   "Làm sao đảm bảo danh mục theo dõi (Watchlist) của người dùng A không bị người dùng B
    //    nhìn thấy hoặc sửa đổi? Và làm sao chống việc người dùng thêm trùng 1 mã nhiều lần?"
    //
    // CÂU TRẢ LỜI CỦA MÃ NGUỒN (CODE TRẢ LỜI):
    //   1. PHÂN QUYỀN SCOPE USER: Mọi thao tác đều nhận `userId` được giải mã từ JWT Token
    //      hợp lệ. Truy vấn `findByUserIdOrderByDisplayOrderAsc(userId)` chỉ lấy dữ liệu của chính User đó.
    //   2. CHỐNG TRÙNG LẶP: Hàm `findByUserIdAndSymbol(userId, cleanSymbol)` kiểm tra trước
    //      khi lưu, nếu đã có sẽ báo lỗi 400 ngay.
    //   3. LÀM GIÀU DỮ LIỆU ĐỘNG: Tự động ghép nối mã theo dõi với giá thị trường và biến động
    //      24h thời gian thực từ Alpha Vantage trước khi trả về cho App Android.
    // ====================================================================================
    public List<WatchlistItemDto> getUserWatchlist(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistItemDto> result = new ArrayList<>();

        for (Watchlist w : items) {
            MarketPriceDto priceDto = marketDataService.getPriceBySymbol(w.getSymbol());
            result.add(new WatchlistItemDto(
                    w.getId(),
                    w.getSymbol(),
                    priceDto != null ? priceDto.getName() : w.getSymbol(),
                    priceDto != null ? priceDto.getCategory() : "MARKET",
                    priceDto != null ? priceDto.getPrice() : null,
                    priceDto != null ? priceDto.getChange24h() : null,
                    w.getDisplayOrder(),
                    w.getCreatedAt()
            ));
        }

        return result;
    }

    /**
     * 🎯 [TÍNH NĂNG CÁ NHÂN HÓA CAO CẤP]:
     * CHỈ GỌI GEMINI AI ĐỂ TÓM TẮT TIN TỨC & DỰ BÁO CHIẾN LƯỢC CHO CÁC MÃ USER ĐÃ TÍCH QUAN TÂM (WATCHLIST)!
     * - Tránh lãng phí Token cho các mã User không quan tâm.
     * - Cung cấp bản tin tài chính tổng hợp chuyên sâu cho danh mục của riêng User đó.
     */
    public List<WatchlistAiInsightDto> getWatchlistAiInsights(Long userId) {
        List<Watchlist> items = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId);
        List<WatchlistAiInsightDto> insights = new ArrayList<>();

        for (Watchlist w : items) {
            String symbol = w.getSymbol();
            MarketPriceDto priceDto = marketDataService.getPriceBySymbol(symbol);

            // 1. Chỉ gọi AI Dự báo chiến lược cho mã User đang theo dõi
            ForecastRequest forecastReq = new ForecastRequest(symbol, "24H_7D");
            ForecastResponse forecast = forecastService.generateForecast(forecastReq);

            // 2. Chỉ lấy các bài báo AI liên quan trực tiếp đến mã User đang theo dõi
            List<NewsFeedItemDto> newsList = aiNewsService.getLiveAiNewsFeed(symbol, 2);

            insights.add(new WatchlistAiInsightDto(
                    w.getId(),
                    symbol,
                    priceDto != null ? priceDto.getName() : symbol,
                    priceDto != null ? priceDto.getCategory() : "MARKET",
                    priceDto != null ? priceDto.getPrice() : null,
                    priceDto != null ? priceDto.getChange24h() : null,
                    forecast,
                    newsList
            ));
        }

        return insights;
    }

    /**
     * Thêm một mã tài sản vào danh sách theo dõi
     */
    @Transactional
    public WatchlistItemDto addToWatchlist(Long userId, WatchlistRequest request) {
        String cleanSymbol = request.getSymbol().trim().toUpperCase();

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

        MarketPriceDto priceDto = marketDataService.getPriceBySymbol(cleanSymbol);
        return new WatchlistItemDto(
                watchlist.getId(),
                cleanSymbol,
                priceDto != null ? priceDto.getName() : cleanSymbol,
                priceDto != null ? priceDto.getCategory() : "MARKET",
                priceDto != null ? priceDto.getPrice() : null,
                priceDto != null ? priceDto.getChange24h() : null,
                watchlist.getDisplayOrder(),
                watchlist.getCreatedAt()
        );
    }

    /**
     * Xóa một mã khỏi danh sách theo dõi
     */
    @Transactional
    public void removeFromWatchlist(Long userId, String symbol) {
        String cleanSymbol = symbol.trim().toUpperCase();
        watchlistRepository.deleteByUserIdAndSymbol(userId, cleanSymbol);
        log.info("XÓA WATCHLIST THÀNH CÔNG | userId={} | symbol={}", userId, cleanSymbol);
    }
}
