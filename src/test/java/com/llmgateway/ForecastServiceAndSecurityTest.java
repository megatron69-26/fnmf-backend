package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.ForecastController;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.GlobalExceptionHandler;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.service.ForecastCacheService;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.GeminiForecastClient;
import com.llmgateway.service.MarketDataService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ForecastServiceAndSecurityTest {

    private MarketForecastRepository forecastRepository;
    private NewsAiCacheRepository newsAiCacheRepository;
    private MarketDataService marketDataService;
    private GeminiForecastClient geminiForecastClient;
    private ForecastCacheService forecastCacheService;
    private ForecastService forecastService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        forecastRepository = mock(MarketForecastRepository.class);
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        marketDataService = mock(MarketDataService.class);
        geminiForecastClient = mock(GeminiForecastClient.class);
        objectMapper = new ObjectMapper();

        forecastCacheService = new ForecastCacheService(forecastRepository, objectMapper);
        forecastService = new ForecastService(
                forecastRepository,
                newsAiCacheRepository,
                marketDataService,
                forecastCacheService,
                geminiForecastClient
        );

        ForecastController controller = new ForecastController(forecastService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ForecastResponse createSampleGeminiResponse(String symbol) {
        return new ForecastResponse(
                symbol,
                symbol + " Asset",
                new BigDecimal("65000.00"),
                "BULLISH_UPTREND",
                "24H_7D",
                new BigDecimal("63000.00"),
                new BigDecimal("68000.00"),
                "BUY",
                85,
                List.of("Luận điểm tăng trưởng 1", "Luận điểm tăng trưởng 2"),
                "Nhận định kỹ thuật chi tiết.",
                "Nhận định vĩ mô chi tiết.",
                "GEMINI",
                30,
                false,
                LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("Cache chứa bản ghi cũ nguồn HEURISTIC hoặc null bị bỏ qua và buộc gọi Gemini mới")
    void testNonGeminiCacheIsIgnored() {
        String symbol = "BTCUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);

        // Bản ghi cũ từ HEURISTIC
        MarketForecast heuristicRecord = new MarketForecast(
                symbol, new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("62000"), new BigDecimal("68000"), "BUY",
                new BigDecimal("80"), "[\"Ý cũ\"]", "Nhận định cũ", "Vĩ mô cũ",
                "HEURISTIC", 5
        );
        heuristicRecord.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.of(heuristicRecord));

        when(marketDataService.getCandles(eq(symbol), anyString())).thenReturn(List.of(
                new CandleDto("2026-09-10", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000"))
        ));

        ForecastResponse geminiResponse = createSampleGeminiResponse(symbol);
        when(geminiForecastClient.requestForecast(eq(symbol), any(), anyList(), anyList(), anyString()))
                .thenReturn(geminiResponse);

        ForecastResponse result = forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D"));

        assertNotNull(result);
        assertEquals("GEMINI", result.getAnalysisSource());
        assertFalse(result.isFromCache());
        verify(geminiForecastClient, times(1)).requestForecast(eq(symbol), any(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("Cache chứa bản ghi nguồn GEMINI còn hạn (dưới 15p) được trả về ngay không gọi Gemini")
    void testGeminiFreshCacheIsReturned() {
        String symbol = "ETHUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "Ethereum", new BigDecimal("3500.00"), new BigDecimal("1.2"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);

        MarketForecast geminiRecord = new MarketForecast(
                symbol, new BigDecimal("3500.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("3300"), new BigDecimal("3700"), "BUY",
                new BigDecimal("88"), "[\"Dòng vốn tổ chức tiếp tục gia tăng mạnh\", \"RSI kỹ thuật duy trì trên vùng hỗ trợ\"]",
                "Nhận định GEMINI kỹ thuật rõ ràng", "Vĩ mô GEMINI khả quan",
                "GEMINI", 30
        );
        geminiRecord.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.of(geminiRecord));

        ForecastResponse result = forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D"));

        assertNotNull(result);
        assertTrue(result.isFromCache());
        assertEquals("GEMINI", result.getAnalysisSource());
        verify(geminiForecastClient, never()).requestForecast(anyString(), any(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("Cache hỏng hoặc chứa ít hơn 2 key drivers bị bỏ qua và gọi Gemini AI mới")
    void testCorruptOrInvalidCacheFallsBackToGemini() {
        String symbol = "SOLUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "Solana", new BigDecimal("150.00"), new BigDecimal("3.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);

        // Bản ghi cache mang nhãn GEMINI nhưng chỉ có 1 ý (không đạt chuẩn chất lượng)
        MarketForecast badCache = new MarketForecast(
                symbol, new BigDecimal("150.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("140"), new BigDecimal("160"), "BUY",
                new BigDecimal("80"), "[\"Chỉ có 1 ý\"]", "Nhận định ngắn", "Vĩ mô ngắn",
                "GEMINI", 30
        );
        badCache.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.of(badCache));

        CandleDto candle = new CandleDto("2026-09-11", BigDecimal.valueOf(145), BigDecimal.valueOf(152), BigDecimal.valueOf(144), BigDecimal.valueOf(150), BigDecimal.valueOf(5000));
        when(marketDataService.getCandles(symbol, "daily")).thenReturn(List.of(candle));

        ForecastResponse freshGemini = new ForecastResponse(
                symbol, "Solana", new BigDecimal("150.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("140.00"), new BigDecimal("160.00"), "BUY", 85,
                List.of("Khối lượng giao dịch tăng đột biến", "Hệ sinh thái ghi nhận TVL tăng mạnh"),
                "Nhận định kỹ thuật chi tiết", "Nhận định vĩ mô chi tiết",
                "GEMINI", 1, false, LocalDateTime.now()
        );
        when(geminiForecastClient.requestForecast(eq(symbol), any(), anyList(), anyList(), anyString()))
                .thenReturn(freshGemini);

        ForecastResponse result = forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D"));

        assertNotNull(result);
        assertFalse(result.isFromCache());
        assertEquals("GEMINI", result.getAnalysisSource());
        verify(geminiForecastClient, times(1)).requestForecast(eq(symbol), any(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("Cache hỏng và Gemini AI cũng lỗi thì ném ForecastUnavailableException (HTTP 503)")
    void testCorruptCacheAndGeminiFailsThrows503() {
        String symbol = "BNBUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "BNB", new BigDecimal("550.00"), new BigDecimal("0.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);

        // Cache rỗng keyDrivers
        MarketForecast emptyDriversCache = new MarketForecast(
                symbol, new BigDecimal("550.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("500"), new BigDecimal("600"), "BUY",
                new BigDecimal("80"), "[]", "Nhận định chi tiết", "Vĩ mô chi tiết",
                "GEMINI", 30
        );
        emptyDriversCache.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.of(emptyDriversCache));

        when(marketDataService.getCandles(symbol, "daily")).thenReturn(List.of());

        assertThrows(ForecastUnavailableException.class, () ->
                forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D")));
    }

    @Test
    @DisplayName("Khi giá thị trường không khả dụng hoặc stale, ném MarketDataUnavailableException")
    void testMarketDataUnavailableThrows() {
        String symbol = "BTCUSDT";
        MarketPriceDto stalePrice = new MarketPriceDto(symbol, "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), true, "CACHE_STALE");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(stalePrice);

        assertThrows(MarketDataUnavailableException.class, () ->
                forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D")));
    }

    @Test
    @DisplayName("Khi Gemini thất bại hoặc thiếu key, ném ForecastUnavailableException (Zero Fake / Zero Slop)")
    void testGeminiFailureThrowsForecastUnavailableException() {
        String symbol = "BTCUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.empty());

        when(geminiForecastClient.requestForecast(eq(symbol), any(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));

        assertThrows(ForecastUnavailableException.class, () ->
                forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D")));
    }

    @Test
    @DisplayName("GET /api/forecast/{symbol} trả HTTP 503 khi ForecastUnavailableException xảy ra")
    void testGetForecastUnavailableReturns503() throws Exception {
        String symbol = "BTCUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.empty());

        when(geminiForecastClient.requestForecast(eq(symbol), any(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("Dịch vụ AI không phản hồi"));

        mockMvc.perform(get("/api/forecast/" + symbol))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Dịch vụ AI không phản hồi"));
    }

    @Test
    @DisplayName("POST /api/forecast/analyze trả HTTP 503 khi không thể tạo dự báo")
    void testPostAnalyzeUnavailableReturns503() throws Exception {
        String symbol = "ETHUSDT";
        MarketPriceDto price = new MarketPriceDto(symbol, "Ethereum", new BigDecimal("3500.00"), new BigDecimal("1.2"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol(symbol)).thenReturn(price);
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc(symbol)).thenReturn(Optional.empty());

        when(geminiForecastClient.requestForecast(eq(symbol), any(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));

        ForecastRequest req = new ForecastRequest(symbol, "24H_7D");
        mockMvc.perform(post("/api/forecast/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Đăng ký tài khoản với mật khẩu dưới 8 ký tự phải bị từ chối")
    void testRegisterPasswordMinLength8() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();

        RegisterRequest reqInvalid = new RegisterRequest("test@fnmf.com", "pass123", "Test User"); // 7 ký tự
        assertFalse(validator.validate(reqInvalid).isEmpty(), "Mật khẩu 7 ký tự phải bị Bean Validation từ chối");

        RegisterRequest reqValid = new RegisterRequest("test@fnmf.com", "pass1234", "Test User"); // 8 ký tự
        assertTrue(validator.validate(reqValid).isEmpty(), "Mật khẩu 8 ký tự phải hợp lệ");
    }
}
