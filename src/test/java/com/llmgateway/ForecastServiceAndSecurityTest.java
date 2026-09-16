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
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);
        when(marketDataService.getAllPrices()).thenReturn(List.of(price));

        // Bản ghi cũ từ HEURISTIC cho MARKET
        MarketForecast heuristicRecord = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("62000"), new BigDecimal("68000"), "BUY",
                new BigDecimal("80"), "[\"Ý cũ\"]", "Nhận định cũ", "Vĩ mô cũ",
                "HEURISTIC", 5
        );
        heuristicRecord.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.of(heuristicRecord));

        when(marketDataService.getCandles(eq("BTCUSDT"), anyString())).thenReturn(List.of(
                new CandleDto("2026-09-10", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000"))
        ));

        ForecastResponse geminiResponse = createSampleGeminiResponse("MARKET");
        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(geminiResponse);

        ForecastResponse result = forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D"));

        assertNotNull(result);
        assertEquals("GEMINI", result.getAnalysisSource());
        assertFalse(result.isFromCache());
        verify(geminiForecastClient, times(1)).requestMarketForecast(anyList(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("Cache chứa bản ghi nguồn GEMINI còn hạn (dưới 15p) được trả về ngay không gọi Gemini")
    void testGeminiFreshCacheIsReturned() {
        String symbol = "ETHUSDT";
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("1.2"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);

        MarketForecast geminiRecord = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("63000"), new BigDecimal("67000"), "BUY",
                new BigDecimal("88"), "[\"Dòng vốn tổ chức tiếp tục gia tăng mạnh\", \"RSI kỹ thuật duy trì trên vùng hỗ trợ\"]",
                "Nhận định GEMINI kỹ thuật rõ ràng", "Vĩ mô GEMINI khả quan",
                "GEMINI", 30
        );
        geminiRecord.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.of(geminiRecord));

        ForecastResponse result = forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D"));

        assertNotNull(result);
        assertTrue(result.isFromCache());
        assertEquals("GEMINI", result.getAnalysisSource());
        verify(geminiForecastClient, never()).requestMarketForecast(anyList(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("Cache hỏng hoặc chứa ít hơn 2 key drivers bị bỏ qua và gọi Gemini AI mới")
    void testCorruptOrInvalidCacheFallsBackToGemini() {
        String symbol = "SOLUSDT";
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("3.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);
        when(marketDataService.getAllPrices()).thenReturn(List.of(price));

        // Bản ghi cache mang nhãn GEMINI nhưng chỉ có 1 ý (không đạt chuẩn chất lượng)
        MarketForecast badCache = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("64000"), new BigDecimal("66000"), "BUY",
                new BigDecimal("80"), "[\"Chỉ có 1 ý\"]", "Nhận định ngắn", "Vĩ mô ngắn",
                "GEMINI", 30
        );
        badCache.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.of(badCache));

        CandleDto candle = new CandleDto("2026-09-11", BigDecimal.valueOf(64000), BigDecimal.valueOf(66000), BigDecimal.valueOf(63500), BigDecimal.valueOf(65000), BigDecimal.valueOf(5000));
        when(marketDataService.getCandles("BTCUSDT", "daily")).thenReturn(List.of(candle));

        ForecastResponse freshGemini = new ForecastResponse(
                "MARKET", "Nhận định toàn thị trường", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("64000.00"), new BigDecimal("66000.00"), "BUY", 85,
                List.of("Khối lượng giao dịch tăng đột biến", "Hệ sinh thái ghi nhận TVL tăng mạnh"),
                "Nhận định kỹ thuật chi tiết", "Nhận định vĩ mô chi tiết",
                "GEMINI", 1, false, LocalDateTime.now()
        );
        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(freshGemini);

        ForecastResponse result = forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D"));

        assertNotNull(result);
        assertFalse(result.isFromCache());
        assertEquals("GEMINI", result.getAnalysisSource());
        verify(geminiForecastClient, times(1)).requestMarketForecast(anyList(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("Cache hỏng và Gemini AI cũng lỗi thì ném ForecastUnavailableException (HTTP 503)")
    void testCorruptCacheAndGeminiFailsThrows503() {
        String symbol = "BNBUSDT";
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("0.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);
        when(marketDataService.getAllPrices()).thenReturn(List.of(price));

        // Cache rỗng keyDrivers
        MarketForecast emptyDriversCache = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("64000"), new BigDecimal("66000"), "BUY",
                new BigDecimal("80"), "[]", "Nhận định chi tiết", "Vĩ mô chi tiết",
                "GEMINI", 30
        );
        emptyDriversCache.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.of(emptyDriversCache));

        when(marketDataService.getCandles("BTCUSDT", "daily")).thenReturn(List.of());
        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("AI unavailable"));

        assertThrows(ForecastUnavailableException.class, () ->
                forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D")));
    }

    @Test
    @DisplayName("Khi giá thị trường không khả dụng hoặc stale, ném ForecastUnavailableException")
    void testMarketDataUnavailableThrows() {
        String symbol = "BTCUSDT";
        MarketPriceDto stalePrice = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), true, "CACHE_STALE");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(stalePrice);
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());

        assertThrows(ForecastUnavailableException.class, () ->
                forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D")));
    }

    @Test
    @DisplayName("Khi Gemini thất bại hoặc thiếu key, ném ForecastUnavailableException (Zero Fake / Zero Slop)")
    void testGeminiFailureThrowsForecastUnavailableException() {
        String symbol = "BTCUSDT";
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);
        when(marketDataService.getAllPrices()).thenReturn(List.of(price));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());

        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));

        assertThrows(ForecastUnavailableException.class, () ->
                forecastService.generateForecast(new ForecastRequest(symbol, "24H_7D")));
    }

    @Test
    @DisplayName("GET /api/forecast/{symbol} trả HTTP 503 khi ForecastUnavailableException xảy ra")
    void testGetForecastUnavailableReturns503() throws Exception {
        String symbol = "BTCUSDT";
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);
        when(marketDataService.getAllPrices()).thenReturn(List.of(price));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());

        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("Dịch vụ AI không phản hồi"));

        mockMvc.perform(get("/api/forecast/" + symbol))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));
    }

    @Test
    @DisplayName("POST /api/forecast/analyze trả HTTP 503 khi không thể tạo dự báo")
    void testPostAnalyzeUnavailableReturns503() throws Exception {
        String symbol = "ETHUSDT";
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("1.2"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(price);
        when(marketDataService.getAllPrices()).thenReturn(List.of(price));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());

        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenThrow(new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));

        ForecastRequest req = new ForecastRequest(symbol, "24H_7D");
        mockMvc.perform(post("/api/forecast/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));
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

    @Test
    @DisplayName("GeminiForecastClient cấu hình timeout 40s và ném ForecastUnavailableException an toàn khi HttpTimeoutException")
    void testGeminiForecastClientTimeout40sAndHttpTimeoutHandling() throws Exception {
        assertEquals(java.time.Duration.ofSeconds(40), GeminiForecastClient.REQUEST_TIMEOUT);

        java.net.http.HttpClient mockHttp = mock(java.net.http.HttpClient.class);
        GeminiForecastClient client = new GeminiForecastClient(objectMapper, mockHttp);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "geminiApiKey", "test-api-key");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "geminiApiUrl", "https://api.example.com/gemini");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "geminiModel", "gemini-3.6-flash");

        when(mockHttp.send(any(java.net.http.HttpRequest.class), any()))
                .thenThrow(new java.net.http.HttpTimeoutException("request timed out after 40s"));

        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        List<CandleDto> candles = List.of(new CandleDto("2026-09-15", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000")));

        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () ->
                client.requestForecast("BTCUSDT", price, candles, List.of(), "24H_7D"));

        assertEquals("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.", ex.getMessage());

        org.mockito.ArgumentCaptor<java.net.http.HttpRequest> reqCaptor = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(mockHttp, times(1)).send(reqCaptor.capture(), any());

        java.net.http.HttpRequest capturedReq = reqCaptor.getValue();
        assertEquals(java.time.Duration.ofSeconds(40), capturedReq.timeout().orElse(null));
    }
}
