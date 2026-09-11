package com.llmgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.repository.MarketForecastRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Dịch vụ quản lý bộ nhớ đệm (Cache) dự báo thị trường trong CSDL.
 * CHÍNH SÁCH BẢO VỆ:
 * - Chỉ chấp nhận bản ghi có `analysis_source = 'GEMINI'`.
 * - Bỏ qua hoàn toàn các bản ghi heuristic hoặc dữ liệu cũ không rõ nguồn gốc.
 */
@Service
public class ForecastCacheService {

    private static final Logger log = LoggerFactory.getLogger(ForecastCacheService.class);
    public static final int FORECAST_CACHE_MINUTES = 15;
    public static final String REQUIRED_SOURCE = "GEMINI";

    private final MarketForecastRepository forecastRepository;
    private final ObjectMapper objectMapper;

    public ForecastCacheService(MarketForecastRepository forecastRepository, ObjectMapper objectMapper) {
        this.forecastRepository = forecastRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Lấy bản dự báo còn hạn từ CSDL nếu thoả mãn:
     * 1. Tạo trong vòng 15 phút.
     * 2. Nguồn phân tích là GEMINI (Zero Heuristic).
     */
    public Optional<ForecastResponse> getFreshForecast(String symbol, MarketPriceDto priceDto) {
        String cleanSymbol = symbol.trim().toUpperCase();
        Optional<MarketForecast> cachedOpt = forecastRepository.findTopBySymbolOrderByCreatedAtDesc(cleanSymbol);

        if (cachedOpt.isEmpty()) {
            return Optional.empty();
        }

        MarketForecast cached = cachedOpt.get();

        // 1. Kiểm tra nguồn phân tích: chỉ chấp nhận GEMINI
        if (cached.getAnalysisSource() == null || !REQUIRED_SOURCE.equalsIgnoreCase(cached.getAnalysisSource())) {
            log.info("BỎ QUA CACHE KHÔNG ĐẠT CHUẨN | symbol={} | source={}", cleanSymbol, cached.getAnalysisSource());
            return Optional.empty();
        }

        // 2. Kiểm tra thời hạn cache (15 phút)
        if (cached.getCreatedAt() == null || cached.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(FORECAST_CACHE_MINUTES))) {
            return Optional.empty();
        }

        log.info("LẤY DỰ BÁO TỪ DATABASE CACHE (GEMINI) | symbol={} | recommendation={}", cleanSymbol, cached.getRecommendation());
        
        List<String> keyDrivers = parseKeyDrivers(cached.getAnalysisSummary());
        if (keyDrivers.isEmpty()) {
            log.warn("BỎ QUA CACHE THIẾU HOẶC RỖNG KEY DRIVERS | symbol={}", cleanSymbol);
            return Optional.empty();
        }

        ForecastResponse response = new ForecastResponse(
                cached.getSymbol(),
                (priceDto != null && priceDto.getName() != null) ? priceDto.getName() : cleanSymbol,
                cached.getCurrentPrice(),
                cached.getTrendPrediction(),
                cached.getTimeframe(),
                cached.getSupportLevel(),
                cached.getResistanceLevel(),
                cached.getRecommendation(),
                cached.getConfidenceScore() != null ? cached.getConfidenceScore().intValue() : null,
                keyDrivers,
                cached.getTechnicalOutlook(),
                cached.getFundamentalOutlook(),
                REQUIRED_SOURCE,
                cached.getCandleCount(),
                true,
                cached.getCreatedAt()
        );

        // Mọi bản ghi cache phải qua kiểm định chất lượng khắt khe trước khi trả về
        try {
            ForecastQualityPolicy.validateOrThrow(response);
        } catch (Exception e) {
            log.warn("BỎ QUA CACHE KHÔNG ĐẠT CHUẨN CHẤT LƯỢNG | symbol={} | reason={}", cleanSymbol, e.getMessage());
            return Optional.empty();
        }

        return Optional.of(response);
    }

    /**
     * Lưu bản dự báo AI mới vào CSDL với analysis_source = GEMINI.
     */
    public void saveForecast(ForecastResponse response) {
        if (response == null || !REQUIRED_SOURCE.equalsIgnoreCase(response.getAnalysisSource())) {
            log.warn("Từ chối lưu bản dự báo không rõ nguồn gốc hoặc không phải từ GEMINI");
            return;
        }

        try {
            String driversJson = objectMapper.writeValueAsString(response.getKeyDrivers());
            BigDecimal confidenceBd = response.getConfidenceScore() != null ? BigDecimal.valueOf(response.getConfidenceScore()) : null;

            MarketForecast entity = new MarketForecast(
                    response.getSymbol(),
                    response.getCurrentPrice(),
                    response.getTrendPrediction(),
                    response.getTimeframe(),
                    response.getSupportLevel(),
                    response.getResistanceLevel(),
                    response.getRecommendation(),
                    confidenceBd,
                    driversJson,
                    response.getTechnicalOutlook(),
                    response.getFundamentalOutlook(),
                    REQUIRED_SOURCE,
                    response.getCandleCount()
            );

            forecastRepository.save(entity);
            log.info("ĐÃ LƯU DỰ BÁO AI MỚI VÀO CSDL | symbol={} | recommendation={}", response.getSymbol(), response.getRecommendation());
        } catch (Exception e) {
            log.warn("Không thể lưu dự báo vào CSDL: {}", e.getMessage());
        }
    }

    private List<String> parseKeyDrivers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return list != null ? list : List.of();
        } catch (Exception e) {
            log.warn("Không thể parse keyDrivers từ JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
