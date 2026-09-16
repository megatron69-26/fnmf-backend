package com.llmgateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.ForecastController;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.GlobalExceptionHandler;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.service.ContentRefreshQuotaService;
import com.llmgateway.service.ForecastCacheService;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.GeminiForecastClient;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class MarketForecastBehavioralTest {

    private MarketForecastRepository forecastRepository;
    private NewsAiCacheRepository newsAiCacheRepository;
    private MarketDataService marketDataService;
    private GeminiForecastClient geminiForecastClient;
    private ContentRefreshQuotaService quotaService;
    private JwtUtil jwtUtil;
    private ForecastCacheService forecastCacheService;
    private ForecastService forecastService;
    private ForecastController forecastController;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        forecastRepository = mock(MarketForecastRepository.class);
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        marketDataService = mock(MarketDataService.class);
        geminiForecastClient = mock(GeminiForecastClient.class);
        quotaService = mock(ContentRefreshQuotaService.class);
        jwtUtil = mock(JwtUtil.class);
        objectMapper = new ObjectMapper();

        forecastCacheService = new ForecastCacheService(forecastRepository, objectMapper);
        forecastService = new ForecastService(
                forecastRepository,
                newsAiCacheRepository,
                marketDataService,
                forecastCacheService,
                geminiForecastClient
        );

        forecastController = new ForecastController(forecastService, quotaService, jwtUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(forecastController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ForecastResponse createMarketGeminiResponse() {
        return new ForecastResponse(
                "MARKET",
                "Nhận định toàn thị trường",
                new BigDecimal("65000.00"),
                "BULLISH_UPTREND",
                "24H_7D",
                new BigDecimal("63000.00"),
                new BigDecimal("68000.00"),
                "BUY",
                85,
                List.of("Dòng tiền tổ chức tiếp tục gia tăng mạnh", "Khối lượng giao dịch mở rộng bền vững"),
                "Nhận định kỹ thuật xu hướng tích cực",
                "Nhận định vĩ mô ủng hộ xu hướng tăng",
                "GEMINI",
                30,
                false,
                LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("1. BTC / BNB / DOGE và MARKET dùng chung một cache MARKET duy nhất")
    void testAllSymbolsShareSingleMarketCache() {
        MarketPriceDto btcPrice = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(btcPrice);
        when(marketDataService.getAllPrices()).thenReturn(List.of(btcPrice));
        when(marketDataService.getCandles(eq("BTCUSDT"), anyString())).thenReturn(List.of(
                new CandleDto("2026-09-16", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000"))
        ));

        // Chưa có cache lúc đầu, Gemini được gọi lần đầu
        ForecastResponse geminiResp = createMarketGeminiResponse();
        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(geminiResp);

        // Giả lập lưu cache thành công
        MarketForecast savedRecord = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("63000"), new BigDecimal("68000"), "BUY",
                new BigDecimal("85"), "[\"Dòng tiền tổ chức tiếp tục gia tăng mạnh\", \"Khối lượng giao dịch mở rộng bền vững\"]",
                "Nhận định kỹ thuật xu hướng tích cực", "Nhận định vĩ mô ủng hộ xu hướng tăng",
                "GEMINI", 30
        );
        savedRecord.setCreatedAt(LocalDateTime.now().minusMinutes(2));

        // Lần 1: Gọi BTCUSDT
        ForecastResponse respBtc = forecastService.generateForecast(new ForecastRequest("BTCUSDT", "24H_7D"));
        assertNotNull(respBtc);
        assertEquals("MARKET", respBtc.getSymbol());

        // Sau lần 1, cache MARKET đã tồn tại
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.of(savedRecord));

        // Lần 2: Gọi BNBUSDT
        ForecastResponse respBnb = forecastService.generateForecast(new ForecastRequest("BNBUSDT", "24H_7D"));
        assertNotNull(respBnb);
        assertEquals("MARKET", respBnb.getSymbol());
        assertTrue(respBnb.isFromCache());

        // Lần 3: Gọi DOGEUSDT
        ForecastResponse respDoge = forecastService.generateForecast(new ForecastRequest("DOGEUSDT", "24H_7D"));
        assertNotNull(respDoge);
        assertEquals("MARKET", respDoge.getSymbol());
        assertTrue(respDoge.isFromCache());

        // Lần 4: Gọi MARKET
        ForecastResponse respMarket = forecastService.generateForecast(new ForecastRequest("MARKET", "24H_7D"));
        assertNotNull(respMarket);
        assertEquals("MARKET", respMarket.getSymbol());
        assertTrue(respMarket.isFromCache());

        // Chứng minh: geminiForecastClient.requestMarketForecast chỉ được gọi ĐÚNG 1 LẦN duy nhất
        verify(geminiForecastClient, times(1)).requestMarketForecast(anyList(), anyList(), anyList(), anyString());
        // Chứng minh: CSDL không bao giờ bị truy vấn theo symbol riêng lẻ BTCUSDT, BNBUSDT, DOGEUSDT
        verify(forecastRepository, never()).findTopBySymbolOrderByCreatedAtDesc("BTCUSDT");
        verify(forecastRepository, never()).findTopBySymbolOrderByCreatedAtDesc("BNBUSDT");
        verify(forecastRepository, never()).findTopBySymbolOrderByCreatedAtDesc("DOGEUSDT");
    }

    @Test
    @DisplayName("2. Các endpoint cũ (/{symbol}, /analyze) không bao giờ gọi requestForecast theo từng symbol")
    void testLegacyEndpointsNeverCallRequestForecastBySymbol() throws Exception {
        MarketPriceDto btcPrice = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(btcPrice);
        when(marketDataService.getAllPrices()).thenReturn(List.of(btcPrice));
        when(marketDataService.getCandles(eq("BTCUSDT"), anyString())).thenReturn(List.of(
                new CandleDto("2026-09-16", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000"))
        ));

        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(createMarketGeminiResponse());

        // 1. GET /api/forecast/BTCUSDT
        mockMvc.perform(get("/api/forecast/BTCUSDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"));

        // 2. GET /api/forecast/BNBUSDT
        mockMvc.perform(get("/api/forecast/BNBUSDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"));

        // 3. POST /api/forecast/analyze với DOGEUSDT
        ForecastRequest reqDoge = new ForecastRequest("DOGEUSDT", "24H_7D");
        mockMvc.perform(post("/api/forecast/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqDoge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"));

        // Chứng minh: CẤM TUYỆT ĐỐI gọi requestForecast theo từng symbol
        verify(geminiForecastClient, never()).requestForecast(anyString(), any(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("3. Refresh body chứa BNBUSDT hoặc DOGEUSDT vẫn cưỡng chế MARKET")
    void testRefreshEnforcesMarketSymbol() throws Exception {
        when(jwtUtil.getUserIdFromToken(anyString())).thenReturn(100L);
        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        when(quotaService.acquireRefreshQuota(eq(100L), anyString(), eq("FORECAST")))
                .thenReturn(new RefreshQuotaDto(10, 1, 9, LocalDate.now().toString(), false));

        MarketPriceDto btcPrice = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(btcPrice);
        when(marketDataService.getAllPrices()).thenReturn(List.of(btcPrice));
        when(marketDataService.getCandles(eq("BTCUSDT"), anyString())).thenReturn(List.of(
                new CandleDto("2026-09-16", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000"))
        ));

        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(createMarketGeminiResponse());

        // 1. POST /api/forecast/refresh với body BNBUSDT
        ForecastRequest refreshReqBnb = new ForecastRequest("BNBUSDT", "24H_7D", "client-req-001");
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReqBnb)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"));

        // 2. POST /api/forecast/market/refresh với body DOGEUSDT
        ForecastRequest refreshReqDoge = new ForecastRequest("DOGEUSDT", "24H_7D", "client-req-002");
        mockMvc.perform(post("/api/forecast/market/refresh")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReqDoge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"));

        // Tuyệt đối không gọi requestForecast
        verify(geminiForecastClient, never()).requestForecast(anyString(), any(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("4. Vắng bóng giá BTC thật và vắng cache trả HTTP 503, tuyệt đối không xuất hiện fake 60000")
    void testAbsenceOfRealPriceAndCacheReturns503Without60000() {
        // Không có giá BTC thật
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(null);
        when(marketDataService.getAllPrices()).thenReturn(Collections.emptyList());
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());

        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () ->
                forecastService.generateMarketForecast("24H_7D", false));

        // Phải trả thông báo an toàn cố định
        assertEquals("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.", ex.getMessage());
        // Tuyệt đối không có số 60000 trong message
        assertFalse(ex.getMessage().contains("60000"));

        // Kiểm tra trực tiếp GeminiForecastClient với allPrices thiếu BTC
        GeminiForecastClient directClient = new GeminiForecastClient(objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(directClient, "geminiApiKey", "test-key");
        List<CandleDto> candles = List.of(new CandleDto("2026-09-16", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE));

        ForecastUnavailableException clientEx = assertThrows(ForecastUnavailableException.class, () ->
                directClient.requestMarketForecast(Collections.emptyList(), candles, Collections.emptyList(), "24H_7D"));

        assertTrue(clientEx.getMessage().contains("Không có dữ liệu giá BTC thực tế để làm mốc tham chiếu nhận định"));
        assertFalse(clientEx.getMessage().contains("60000"));
    }

    @Test
    @DisplayName("5. Provider error không bao giờ lọt log hay API response")
    void testProviderErrorNeverLeaksIntoLogOrResponse() throws Exception {
        String sensitiveLeak = "API_KEY_LEAK: AIzaSyD987654321_secret_token_error_upstream";

        MarketPriceDto btcPrice = new MarketPriceDto("BTCUSDT", "Bitcoin", new BigDecimal("65000.00"), new BigDecimal("2.5"), false, "BINANCE_REALTIME");
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(btcPrice);
        when(marketDataService.getAllPrices()).thenReturn(List.of(btcPrice));
        when(marketDataService.getCandles(eq("BTCUSDT"), anyString())).thenReturn(List.of(
                new CandleDto("2026-09-16", new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("63500"), new BigDecimal("65000"), new BigDecimal("1000"))
        ));
        when(forecastRepository.findTopBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Optional.empty());

        // Gemini ném lỗi chứa thông tin nhạy cảm của provider
        when(geminiForecastClient.requestMarketForecast(anyList(), anyList(), anyList(), anyString()))
                .thenThrow(new RuntimeException(sensitiveLeak));

        // Bắt log của ForecastService và GlobalExceptionHandler
        Logger forecastServiceLogger = (Logger) LoggerFactory.getLogger(ForecastService.class);
        Logger globalExceptionLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        forecastServiceLogger.addAppender(appender);
        globalExceptionLogger.addAppender(appender);

        try {
            MvcResult result = mockMvc.perform(get("/api/forecast/BTCUSDT"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("ERROR"))
                    .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."))
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            // Tuyệt đối không lọt vào API response
            assertFalse(responseBody.contains("AIzaSy"), "Response body không được chứa API key hay provider secret");
            assertFalse(responseBody.contains(sensitiveLeak), "Response body không được chứa sensitive error message");

            // Kiểm tra log không chứa sensitive error message
            for (ILoggingEvent event : appender.list) {
                String formattedMessage = event.getFormattedMessage();
                assertFalse(formattedMessage.contains("AIzaSy"), "Log không được chứa provider API key: " + formattedMessage);
                assertFalse(formattedMessage.contains(sensitiveLeak), "Log không được chứa sensitive leak: " + formattedMessage);
            }
        } finally {
            forecastServiceLogger.detachAppender(appender);
            globalExceptionLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("6. Replay khi Binance/Gemini đều lỗi: trả cache MARKET, tuyệt đối không gọi marketDataService hay Gemini")
    void testReplayWithValidCache_neverCallsMarketDataOrGemini() throws Exception {
        when(jwtUtil.getUserIdFromToken(anyString())).thenReturn(100L);
        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        // Quota trả về replay = true
        when(quotaService.acquireRefreshQuota(eq(100L), anyString(), eq("FORECAST")))
                .thenReturn(new RefreshQuotaDto(10, 1, 9, LocalDate.now().toString(), true));

        // CSDL có bản ghi MARKET hợp lệ nguồn GEMINI
        MarketForecast cachedGemini = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("63000"), new BigDecimal("68000"), "BUY",
                new BigDecimal("85"), "[\"Dòng vốn tổ chức dồi dào\", \"RSI kỹ thuật tích cực\"]",
                "Nhận định kỹ thuật rõ ràng", "Nhận định vĩ mô khả quan",
                "GEMINI", 30
        );
        cachedGemini.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(List.of(cachedGemini));

        // Thực hiện POST /api/forecast/refresh trong trạng thái replay
        ForecastRequest refreshReq = new ForecastRequest("BNBUSDT", "24H_7D", "replay-req-123");
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"))
                .andExpect(jsonPath("$.fromCache").value(true));

        // Thực hiện POST /api/forecast/market/refresh trong trạng thái replay
        mockMvc.perform(post("/api/forecast/market/refresh")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("MARKET"))
                .andExpect(jsonPath("$.fromCache").value(true));

        // Tuyệt đối KHÔNG gọi marketDataService hay geminiForecastClient
        verifyNoInteractions(marketDataService);
        verifyNoInteractions(geminiForecastClient);
    }

    @Test
    @DisplayName("7. Replay khi không có cache: trả 503 an toàn, tuyệt đối không gọi marketDataService hay Gemini")
    void testReplayWithNoCache_neverCallsMarketDataOrGemini_returns503Safe() throws Exception {
        when(jwtUtil.getUserIdFromToken(anyString())).thenReturn(100L);
        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        when(quotaService.acquireRefreshQuota(eq(100L), anyString(), eq("FORECAST")))
                .thenReturn(new RefreshQuotaDto(10, 1, 9, LocalDate.now().toString(), true));

        // CSDL không có bản ghi nào cho MARKET
        when(forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET")).thenReturn(Collections.emptyList());

        ForecastRequest refreshReq = new ForecastRequest("MARKET", "24H_7D", "replay-req-empty");
        mockMvc.perform(post("/api/forecast/refresh")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("FORECAST_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau."));

        // Tuyệt đối KHÔNG gọi marketDataService hay geminiForecastClient
        verifyNoInteractions(marketDataService);
        verifyNoInteractions(geminiForecastClient);
    }

    @Test
    @DisplayName("8. Stale fallback tìm bản MARKET nguồn GEMINI hợp lệ gần nhất, bỏ qua bản mới hơn nếu sai nguồn")
    void testStaleFallback_findsNearestValidGeminiRecord() {
        // Binance và Gemini đều lỗi
        when(marketDataService.getPriceBySymbol("BTCUSDT")).thenReturn(null);
        when(marketDataService.getAllPrices()).thenReturn(Collections.emptyList());

        // Bản mới nhất (createdAt = -1 min) là HEURISTIC (sai nguồn)
        MarketForecast newestHeuristic = new MarketForecast(
                "MARKET", new BigDecimal("60000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("58000"), new BigDecimal("62000"), "BUY",
                new BigDecimal("70"), "[\"Lý do 1\", \"Lý do 2\"]",
                "Nhận định kỹ thuật", "Nhận định vĩ mô",
                "HEURISTIC", 5
        );
        newestHeuristic.setCreatedAt(LocalDateTime.now().minusMinutes(1));

        // Bản tiếp theo (createdAt = -5 min) là GEMINI nhưng rỗng key drivers (hỏng)
        MarketForecast badGemini = new MarketForecast(
                "MARKET", new BigDecimal("62000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("60000"), new BigDecimal("64000"), "BUY",
                new BigDecimal("70"), "[]",
                "Nhận định kỹ thuật", "Nhận định vĩ mô",
                "GEMINI", 5
        );
        badGemini.setCreatedAt(LocalDateTime.now().minusMinutes(5));

        // Bản cũ hơn (createdAt = -10 min) là GEMINI chuẩn chất lượng
        MarketForecast validOldGemini = new MarketForecast(
                "MARKET", new BigDecimal("65000.00"), "BULLISH_UPTREND", "24H_7D",
                new BigDecimal("63000"), new BigDecimal("67000"), "BUY",
                new BigDecimal("85"), "[\"Dòng vốn tổ chức mở rộng mạnh mẽ\", \"Thanh khoản thị trường duy trì ổn định\"]",
                "Nhận định kỹ thuật chi tiết", "Nhận định vĩ mô khả quan",
                "GEMINI", 30
        );
        validOldGemini.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        when(forecastRepository.findBySymbolOrderByCreatedAtDesc("MARKET"))
                .thenReturn(List.of(newestHeuristic, badGemini, validOldGemini));

        ForecastResponse result = forecastService.generateMarketForecast("24H_7D", false);

        assertNotNull(result);
        assertEquals("MARKET", result.getSymbol());
        assertEquals("GEMINI", result.getAnalysisSource());
        assertTrue(result.isStale());
        assertEquals(new BigDecimal("65000.00"), result.getCurrentPrice());
    }

    @Test
    @DisplayName("9. Cấu hình shard cho MARKET độc lập và không fallback chéo")
    void testGeminiShardRouter_marketRoutingConfigurable() {
        com.llmgateway.service.provider.GeminiShardRouter router = new com.llmgateway.service.provider.GeminiShardRouter();
        router.setShardKeys("valid-key-shard-1", "valid-key-shard-2", "valid-key-shard-3");

        // 1. Mặc định là SHARD_1
        assertEquals("GEMINI_SHARD_1", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_1", router.resolveShard("MARKET").shardName());
        assertEquals("valid-key-shard-1", router.resolveShard("MARKET").apiKey());

        // 2. Chuyển cấu hình cố định sang SHARD_2
        router.setMarketShard("GEMINI_SHARD_2");
        assertEquals("GEMINI_SHARD_2", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_2", router.resolveShard("MARKET").shardName());
        assertEquals("valid-key-shard-2", router.resolveShard("MARKET").apiKey());

        // 3. Không fallback chéo: nếu shard được cấu hình thiếu key, ném ForecastUnavailableException
        router.setShardKeys("valid-key-shard-1", "", "valid-key-shard-3");
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShard("MARKET"));
    }

    @Test
    @DisplayName("10. GEMINI_MARKET_SHARD sai giá trị phải fail-closed, null/rỗng mặc định Shard 1")
    void testGeminiShardRouter_invalidMarketShardValue_failsClosed() {
        com.llmgateway.service.provider.GeminiShardRouter router = new com.llmgateway.service.provider.GeminiShardRouter();
        router.setShardKeys("valid-key-shard-1", "valid-key-shard-2", "valid-key-shard-3");

        // 1. Null / rỗng / khoảng trắng mặc định sang GEMINI_SHARD_1
        router.setMarketShard(null);
        assertEquals("GEMINI_SHARD_1", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_1", router.resolveShard("MARKET").shardName());

        router.setMarketShard("");
        assertEquals("GEMINI_SHARD_1", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_1", router.resolveShard("MARKET").shardName());

        router.setMarketShard("   ");
        assertEquals("GEMINI_SHARD_1", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_1", router.resolveShard("MARKET").shardName());

        // 2. Chấp nhận chính xác GEMINI_SHARD_1, GEMINI_SHARD_2, GEMINI_SHARD_3
        router.setMarketShard("GEMINI_SHARD_1");
        assertEquals("GEMINI_SHARD_1", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_1", router.resolveShard("MARKET").shardName());

        router.setMarketShard("GEMINI_SHARD_2");
        assertEquals("GEMINI_SHARD_2", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_2", router.resolveShard("MARKET").shardName());

        router.setMarketShard("GEMINI_SHARD_3");
        assertEquals("GEMINI_SHARD_3", router.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_3", router.resolveShard("MARKET").shardName());

        // 3. Giá trị sai hoặc không hợp lệ PHẢI fail-closed, tuyệt đối không âm thầm dùng Shard 1
        router.setMarketShard("INVALID_SHARD");
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShard("MARKET"));
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShardName("MARKET"));

        router.setMarketShard("GEMINI_SHARD_4");
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShard("MARKET"));
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShardName("MARKET"));

        router.setMarketShard("SHARD_1");
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShard("MARKET"));
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShardName("MARKET"));

        router.setMarketShard("RANDOM_TEXT_123");
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShard("MARKET"));
        assertThrows(ForecastUnavailableException.class, () -> router.resolveShardName("MARKET"));
    }
}

