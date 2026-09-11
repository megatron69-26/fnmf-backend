package com.llmgateway.service;

import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(forecastRepository,
             newsAiCacheRepository,
             marketDataService,
             new ForecastCacheService(forecastRepository, objectMapper),
             new GeminiForecastClient(objectMapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ForecastService(MarketForecastRepository forecastRepository,
                           NewsAiCacheRepository newsAiCacheRepository,
                           MarketDataService marketDataService,
                           ForecastCacheService forecastCacheService,
                           GeminiForecastClient geminiForecastClient) {
        this.forecastRepository = forecastRepository;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.marketDataService = marketDataService;
        this.forecastCacheService = forecastCacheService;
        this.geminiForecastClient = geminiForecastClient;
    }

    /**
     * Tạo hoặc lấy bản dự báo thị trường AI dựa trên:
     * 1. Kiểm tra giá thị trường thời gian thực (Zero Fake / Zero Stale).
     * 2. Kiểm tra bộ nhớ đệm CSDL (15 phút) - chỉ chấp nhận nguồn GEMINI.
     * 3. Thu thập nến thực tế (tối đa 30 cây nến) và tin tức vĩ mô.
     * 4. Gọi Gemini AI và kiểm duyệt nghiêm ngặt theo ForecastQualityPolicy.
     * 5. Lưu vào CSDL và trả về kết quả.
     * Khi có lỗi mạng/AI/thiếu nến/không đạt chuẩn: ném ForecastUnavailableException (HTTP 503),
     * tuyệt đối không sinh dữ liệu heuristic bịa đặt.
     */
    public ForecastResponse generateForecast(ForecastRequest request) {
        if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Mã tài sản không được để trống");
        }

        String cleanSymbol = request.getSymbol().trim().toUpperCase();

        // 1. Kiểm tra tính khả dụng của giá thị trường thời gian thực
        MarketPriceDto priceDto = marketDataService.getPriceBySymbol(cleanSymbol);
        if (priceDto == null || Boolean.TRUE.equals(priceDto.isStale()) || priceDto.getPrice() == null) {
            throw new MarketDataUnavailableException("Dữ liệu thị trường thời gian thực không khả dụng hoặc bị cũ (stale), không thể tạo dự báo cho mã: " + cleanSymbol);
        }

        // 2. Kiểm tra CSDL xem có bản dự báo còn hạn từ nguồn GEMINI hay không
        Optional<ForecastResponse> cached = forecastCacheService.getFreshForecast(cleanSymbol, priceDto);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 3. Thu thập dữ liệu nến thực tế từ sàn & tin tức CSDL
        List<CandleDto> candles = marketDataService.getCandles(cleanSymbol, "daily");
        List<NewsAiCache> recentNews = newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc();

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
}
