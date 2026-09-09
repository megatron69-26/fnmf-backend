package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.controller.WatchlistController;
import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.entity.Watchlist;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.exception.UnauthorizedException;
import com.llmgateway.repository.MarketForecastRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.WatchlistRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.BinanceMarketClient;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.service.WatchlistService;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MarketProviderFailoverAndSecurityTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
    }

    // =========================================================================
    // 1. BINANCE MARKET CLIENT - CONFIG & FAILOVER TESTS
    // =========================================================================

    @Test
    @DisplayName("BinanceMarketClient chuẩn hóa URL và sử dụng default primary Binance Vision & secondary GCP")
    public void testBinanceMarketClient_baseUrlNormalizationAndUrlBuilding() {
        BinanceMarketClient client = new BinanceMarketClient(
                objectMapper,
                "https://data-api.binance.vision/",
                "https://api-gcp.binance.com/"
        );

        assertEquals("https://data-api.binance.vision", client.getPrimaryRestBaseUrl());
        assertEquals("https://api-gcp.binance.com", client.getSecondaryRestBaseUrl());

        // Chuẩn hóa trailing slash
        assertEquals("https://data-api.binance.vision", client.normalizeBaseUrl("https://data-api.binance.vision/"));
        assertEquals("https://data-api.binance.vision", client.normalizeBaseUrl("https://data-api.binance.vision///"));
        assertEquals("https://data-api.binance.vision", client.normalizeBaseUrl("https://data-api.binance.vision"));
        assertEquals("https://data-api.binance.vision", client.normalizeBaseUrl(""));

        // Build URLs
        assertEquals(
                "https://data-api.binance.vision/api/v3/ticker/24hr?symbol=BTCUSDT",
                client.buildTickerUrl("https://data-api.binance.vision/", "BTCUSDT")
        );
        assertEquals(
                "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=30",
                client.buildKlinesUrl("https://data-api.binance.vision/", "BTCUSDT", "daily", 30)
        );
    }

    @Test
    @DisplayName("BinanceMarketClient gọi REST thành công qua primary domain mà không cần failover")
    @SuppressWarnings("unchecked")
    public void testBinanceMarketClient_primarySuccess() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(
                "{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"65000.50\",\"priceChange\":\"1200.00\",\"priceChangePercent\":\"1.88\",\"bidPrice\":\"65000.00\",\"askPrice\":\"65001.00\"}"
        );

        doReturn(mockResponse)
                .when(mockHttpClient)
                .send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());

        BinanceMarketClient client = new BinanceMarketClient(
                objectMapper,
                mockHttpClient,
                "https://data-api.binance.vision",
                "https://api-gcp.binance.com"
        );

        BinanceMarketClient.BinanceTickerResult result = client.fetch24hrTicker("BTCUSDT");

        assertNotNull(result);
        assertEquals(new BigDecimal("65000.50"), result.price());
        assertEquals(new BigDecimal("1.88"), result.change24h());

        // Xác minh chỉ gọi primary domain
        verify(mockHttpClient, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());
        verify(mockHttpClient, never()).send(argThat(req -> req != null && req.uri().toString().contains("api-gcp.binance.com")), any());
    }

    @Test
    @DisplayName("BinanceMarketClient failover sang secondary đúng 1 lần khi primary timeout hoặc lỗi 5xx/403/429/451")
    @SuppressWarnings("unchecked")
    public void testBinanceMarketClient_failoverOnPrimaryFailure() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockSecondaryResponse = mock(HttpResponse.class);

        // Primary ném IOException (Timeout / Network error)
        doThrow(new IOException("Primary connection timed out"))
                .when(mockHttpClient)
                .send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());

        // Secondary trả về 200 OK
        when(mockSecondaryResponse.statusCode()).thenReturn(200);
        when(mockSecondaryResponse.body()).thenReturn(
                "{\"symbol\":\"ETHUSDT\",\"lastPrice\":\"3500.00\",\"priceChange\":\"50.00\",\"priceChangePercent\":\"1.45\",\"bidPrice\":\"3499.00\",\"askPrice\":\"3501.00\"}"
        );
        doReturn(mockSecondaryResponse)
                .when(mockHttpClient)
                .send(argThat(req -> req != null && req.uri().toString().contains("api-gcp.binance.com")), any());

        BinanceMarketClient client = new BinanceMarketClient(
                objectMapper,
                mockHttpClient,
                "https://data-api.binance.vision",
                "https://api-gcp.binance.com"
        );

        BinanceMarketClient.BinanceTickerResult result = client.fetch24hrTicker("ETHUSDT");

        assertNotNull(result);
        assertEquals(new BigDecimal("3500.00"), result.price());

        // Xác nhận gọi primary 1 lần rồi failover sang secondary đúng 1 lần
        verify(mockHttpClient, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());
        verify(mockHttpClient, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("api-gcp.binance.com")), any());
    }

    @Test
    @DisplayName("BinanceMarketClient ném exception khi cả primary và secondary đều lỗi")
    @SuppressWarnings("unchecked")
    public void testBinanceMarketClient_bothPrimaryAndSecondaryFail() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mock503Response = mock(HttpResponse.class);
        when(mock503Response.statusCode()).thenReturn(503);
        when(mock503Response.body()).thenReturn("Service Unavailable");

        doReturn(mock503Response)
                .when(mockHttpClient)
                .send(any(HttpRequest.class), any());

        BinanceMarketClient client = new BinanceMarketClient(
                objectMapper,
                mockHttpClient,
                "https://data-api.binance.vision",
                "https://api-gcp.binance.com"
        );

        assertThrows(IllegalStateException.class, () -> client.fetch24hrTicker("BTCUSDT"));

        // Xác minh gọi primary rồi secondary, không retry vô hạn
        verify(mockHttpClient, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());
        verify(mockHttpClient, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("api-gcp.binance.com")), any());
    }

    @Test
    @DisplayName("BinanceMarketClient: Khi primary trả về HTTP 400 hoặc 404 thì KHÔNG failover sang secondary, chỉ gọi đúng 1 provider")
    @SuppressWarnings("unchecked")
    public void testBinanceMarketClient_nonRetryableClientErrors_doNotFailoverToSecondary() throws Exception {
        // --- 1. Test với HTTP 400 (Bad Request) ---
        HttpClient mockHttpClient400 = mock(HttpClient.class);
        HttpResponse<String> mock400Response = mock(HttpResponse.class);
        when(mock400Response.statusCode()).thenReturn(400);
        when(mock400Response.body()).thenReturn("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}");

        doReturn(mock400Response)
                .when(mockHttpClient400)
                .send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());

        BinanceMarketClient client400 = new BinanceMarketClient(
                objectMapper,
                mockHttpClient400,
                "https://data-api.binance.vision",
                "https://api-gcp.binance.com"
        );

        BinanceMarketClient.NonRetryableMarketException ex400 = assertThrows(
                BinanceMarketClient.NonRetryableMarketException.class,
                () -> client400.fetch24hrTicker("BADSYMBOL")
        );
        assertEquals(400, ex400.getStatusCode());
        // Chỉ gọi primary đúng 1 lần
        verify(mockHttpClient400, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());
        // Tuyệt đối KHÔNG gọi secondary
        verify(mockHttpClient400, never()).send(argThat(req -> req != null && req.uri().toString().contains("api-gcp.binance.com")), any());

        // --- 2. Test với HTTP 404 (Not Found) ---
        HttpClient mockHttpClient404 = mock(HttpClient.class);
        HttpResponse<String> mock404Response = mock(HttpResponse.class);
        when(mock404Response.statusCode()).thenReturn(404);
        when(mock404Response.body()).thenReturn("{\"code\":-1003,\"msg\":\"Endpoint not found.\"}");

        doReturn(mock404Response)
                .when(mockHttpClient404)
                .send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());

        BinanceMarketClient client404 = new BinanceMarketClient(
                objectMapper,
                mockHttpClient404,
                "https://data-api.binance.vision",
                "https://api-gcp.binance.com"
        );

        BinanceMarketClient.NonRetryableMarketException ex404 = assertThrows(
                BinanceMarketClient.NonRetryableMarketException.class,
                () -> client404.fetch24hrTicker("NOTFOUND")
        );
        assertEquals(404, ex404.getStatusCode());
        // Chỉ gọi primary đúng 1 lần
        verify(mockHttpClient404, times(1)).send(argThat(req -> req != null && req.uri().toString().contains("data-api.binance.vision")), any());
        // Tuyệt đối KHÔNG gọi secondary
        verify(mockHttpClient404, never()).send(argThat(req -> req != null && req.uri().toString().contains("api-gcp.binance.com")), any());
    }

    // =========================================================================
    // 2. MARKET DATA SERVICE - RESILIENT MULTI-SYMBOL & STALE CACHING
    // =========================================================================

    @Test
    @DisplayName("MarketDataService: Lỗi 1 symbol không làm mất các symbol khác trong getAllPrices")
    public void testMarketDataService_partialSymbolSuccess() throws Exception {
        BinanceMarketClient mockBinance = mock(BinanceMarketClient.class);
        MarketDataService marketDataService = new MarketDataService(objectMapper, mockBinance);
        marketDataService.clearCache();

        BinanceMarketClient.BinanceTickerResult btcResult = new BinanceMarketClient.BinanceTickerResult(
                new BigDecimal("65000.00"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO
        );
        when(mockBinance.fetch24hrTicker("BTCUSDT")).thenReturn(btcResult);
        when(mockBinance.fetch24hrTicker("ETHUSDT")).thenThrow(new MarketDataUnavailableException("ETH timeout"));
        when(mockBinance.fetch24hrTicker("PAXGUSDT")).thenThrow(new MarketDataUnavailableException("PAXG error"));

        List<MarketPriceDto> prices = marketDataService.getAllPrices();

        assertNotNull(prices);
        assertFalse(prices.isEmpty(), "Danh sách không được rỗng khi có ít nhất 1 symbol thành công");
        assertEquals(1, prices.size());
        assertEquals("BTCUSDT", prices.get(0).getSymbol());
        assertEquals(new BigDecimal("65000.00"), prices.get(0).getPrice());
    }

    @Test
    @DisplayName("MarketDataService: Khi toàn bộ provider lỗi nhưng có cache, trả về cache với stale=true")
    public void testMarketDataService_fallbackToStaleCache() throws Exception {
        BinanceMarketClient mockBinance = mock(BinanceMarketClient.class);
        MarketDataService marketDataService = new MarketDataService(objectMapper, mockBinance);
        marketDataService.clearCache();

        // Bỏ sẵn 1 giá vào cache
        MarketPriceDto cachedBtc = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO", new BigDecimal("64000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "old");
        marketDataService.putPriceInCache(cachedBtc);

        // Mock provider lỗi toàn bộ
        when(mockBinance.fetch24hrTicker(anyString())).thenThrow(new MarketDataUnavailableException("All providers down"));

        List<MarketPriceDto> prices = marketDataService.getAllPrices();

        assertNotNull(prices);
        assertFalse(prices.isEmpty(), "Phải trả về dữ liệu cache khi provider lỗi");
        MarketPriceDto result = prices.get(0);
        assertEquals("BTCUSDT", result.getSymbol());
        assertEquals(new BigDecimal("64000.00"), result.getPrice());
        assertTrue(result.isStale(), "Dữ liệu cache khi provider lỗi phải được đánh dấu stale = true");
    }

    @Test
    @DisplayName("MarketDataService: Khi toàn bộ provider lỗi VÀ không có cache nào, ném MarketDataUnavailableException (503)")
    public void testMarketDataService_allFailNoCache_throws503() throws Exception {
        BinanceMarketClient mockBinance = mock(BinanceMarketClient.class);
        MarketDataService marketDataService = new MarketDataService(objectMapper, mockBinance);
        marketDataService.clearCache();

        when(mockBinance.fetch24hrTicker(anyString())).thenThrow(new MarketDataUnavailableException("Provider down"));

        assertThrows(MarketDataUnavailableException.class, () -> marketDataService.getAllPrices());
    }

    // =========================================================================
    // 3. FORECAST SERVICE - 503 MARKET DATA GUARD (NO MOCK & NO GEMINI CALL)
    // =========================================================================

    @Test
    @DisplayName("ForecastService từ chối dự báo và ném 503 khi MarketDataService trả null, stale=true hoặc provider lỗi")
    public void testForecastService_guardsAgainstMissingOrStaleMarketData() {
        MarketForecastRepository mockForecastRepo = mock(MarketForecastRepository.class);
        NewsAiCacheRepository mockNewsRepo = mock(NewsAiCacheRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        ForecastService forecastService = new ForecastService(mockForecastRepo, mockNewsRepo, mockMarketData, objectMapper);

        ForecastRequest request = new ForecastRequest("BTCUSDT", "24H_7D");

        // Case A: MarketDataService ném MarketDataUnavailableException
        doThrow(new MarketDataUnavailableException("Provider offline"))
                .when(mockMarketData).getPriceBySymbol("BTCUSDT");
        assertThrows(MarketDataUnavailableException.class, () -> forecastService.generateForecast(request));

        // Case B: MarketPriceDto có stale = true
        reset(mockMarketData);
        MarketPriceDto stalePrice = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO", new BigDecimal("65000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "old");
        stalePrice.setStale(true);
        doReturn(stalePrice).when(mockMarketData).getPriceBySymbol("BTCUSDT");
        assertThrows(MarketDataUnavailableException.class, () -> forecastService.generateForecast(request));

        // Case C: MarketPriceDto có price = null
        reset(mockMarketData);
        MarketPriceDto nullPrice = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO", null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "old");
        doReturn(nullPrice).when(mockMarketData).getPriceBySymbol("BTCUSDT");
        assertThrows(MarketDataUnavailableException.class, () -> forecastService.generateForecast(request));

        // Tuyệt đối KHÔNG lưu dự báo mới vào DB khi market data không hợp lệ!
        verify(mockForecastRepo, never()).save(any());
    }

    // =========================================================================
    // 4. WATCHLIST CONTROLLER & SERVICE - AUTH & GRACEFUL NULL PRICE HANDLING
    // =========================================================================

    @Test
    @DisplayName("WatchlistController ném UnauthorizedException (HTTP 401) khi JWT Token thiếu hoặc không hợp lệ")
    public void testWatchlistController_unauthorizedExceptionOnInvalidToken() {
        WatchlistService mockWatchlistService = mock(WatchlistService.class);
        JwtUtil mockJwtUtil = mock(JwtUtil.class);

        WatchlistController controller = new WatchlistController(mockWatchlistService, mockJwtUtil);

        // 1. Header null hoặc rỗng
        assertThrows(UnauthorizedException.class, () -> controller.getWatchlist(null));
        assertThrows(UnauthorizedException.class, () -> controller.getWatchlist(""));
        assertThrows(UnauthorizedException.class, () -> controller.getWatchlist("   "));

        // 2. Không bắt đầu bằng "Bearer "
        assertThrows(UnauthorizedException.class, () -> controller.getWatchlist("Basic token123"));

        // 3. Token không hợp lệ theo JwtUtil
        when(mockJwtUtil.validateToken("invalid-jwt")).thenReturn(false);
        assertThrows(UnauthorizedException.class, () -> controller.getWatchlist("Bearer invalid-jwt"));

        verify(mockWatchlistService, never()).getUserWatchlist(anyLong());
    }

    @Test
    @DisplayName("WatchlistService: Khi MarketDataService lỗi, vẫn trả về danh sách Watchlist với currentPrice=null, không crash 500")
    public void testWatchlistService_gracefulNullPriceOnMarketFailure() {
        WatchlistRepository mockWatchlistRepo = mock(WatchlistRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);
        ForecastService mockForecast = mock(ForecastService.class);
        AiNewsService mockAiNews = mock(AiNewsService.class);

        WatchlistService watchlistService = new WatchlistService(mockWatchlistRepo, mockMarketData, mockForecast, mockAiNews);

        Watchlist item = new Watchlist(1L, "BTCUSDT", 1);
        item.setId(100L);
        when(mockWatchlistRepo.findByUserIdOrderByDisplayOrderAsc(1L)).thenReturn(Collections.singletonList(item));

        // Mock market data service bị lỗi
        doThrow(new MarketDataUnavailableException("Market 503"))
                .when(mockMarketData).getPriceBySymbol("BTCUSDT");

        List<WatchlistItemDto> result = watchlistService.getUserWatchlist(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
        WatchlistItemDto dto = result.get(0);
        assertEquals("BTCUSDT", dto.getSymbol());
        assertNull(dto.getCurrentPrice(), "Khi market data lỗi, giá trong Watchlist phải trả về null");
        assertNull(dto.getChange24h(), "Khi market data lỗi, change24h phải trả về null");
    }
}
