package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.stock.StockCatalogDto;
import com.llmgateway.dto.stock.StockDetailDto;
import com.llmgateway.dto.trade.HoldingDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.dto.trade.PortfolioSummaryDto;
import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.entity.Holding;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.Wallet;
import com.llmgateway.entity.Watchlist;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.exception.UnsupportedSymbolException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.repository.WatchlistRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.service.StockMarketService;
import com.llmgateway.service.TradeService;
import com.llmgateway.service.WatchlistService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class StockMarketAndCatalogTest {

    private ObjectMapper objectMapper;
    private NewsAiCacheRepository newsAiCacheRepository;
    private HttpClient mockHttpClient;
    private StockMarketService stockMarketService;

    private static final String ALPHA_VANTAGE_SAMPLE_JSON = """
            {
                "Meta Data": {
                    "1. Information": "Daily Prices (open, high, low, close) and Volumes",
                    "2. Symbol": "AAPL",
                    "3. Last Refreshed": "2026-09-14",
                    "4. Output Size": "Compact",
                    "5. Time Zone": "US/Eastern"
                },
                "Time Series (Daily)": {
                    "2026-09-14": {
                        "1. open": "220.0000",
                        "2. high": "225.5000",
                        "3. low": "219.0000",
                        "4. close": "224.2500",
                        "5. volume": "55000000"
                    },
                    "2026-09-13": {
                        "1. open": "218.0000",
                        "2. high": "221.0000",
                        "3. low": "217.5000",
                        "4. close": "220.0000",
                        "5. volume": "48000000"
                    }
                }
            }
            """;

    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        mockHttpClient = mock(HttpClient.class);

        stockMarketService = new StockMarketService(objectMapper, newsAiCacheRepository, mockHttpClient);
        org.springframework.test.util.ReflectionTestUtils.setField(stockMarketService, "alphaVantageKey", "test-key-123");
        org.springframework.test.util.ReflectionTestUtils.setField(stockMarketService, "alphaVantageUrl", "https://www.alphavantage.co/query");
        org.springframework.test.util.ReflectionTestUtils.setField(stockMarketService, "geminiApiKey", ""); // Disable Gemini HTTP by default
    }

    // =========================================================================
    // 1. DANH MỤC CỔ PHIẾU: ĐÚNG 8 MÃ, METADATA CỐ ĐỊNH, ZERO EXTERNAL CALLS
    // =========================================================================

    @Test
    @DisplayName("Catalog trả về đúng 8 mã cổ phiếu và không thực hiện bất kỳ network request nào")
    public void testStockCatalog_returnsEightStocksWithoutExternalCalls() throws IOException, InterruptedException {
        List<StockCatalogDto> catalog = stockMarketService.getStockCatalog();

        assertNotNull(catalog);
        assertEquals(8, catalog.size());

        List<String> symbols = catalog.stream().map(StockCatalogDto::symbol).toList();
        assertTrue(symbols.contains("AAPL"));
        assertTrue(symbols.contains("MSFT"));
        assertTrue(symbols.contains("NVDA"));
        assertTrue(symbols.contains("TSLA"));
        assertTrue(symbols.contains("AMZN"));
        assertTrue(symbols.contains("META"));
        assertTrue(symbols.contains("GOOGL"));
        assertTrue(symbols.contains("JPM"));

        verify(mockHttpClient, never()).send(any(), any());
    }

    // =========================================================================
    // 2. DỮ LIỆU THẬT & CACHE 24H: CHỈ GỌI MÃ YÊU CẦU, CACHE CHẶN GỌI LẶP
    // =========================================================================

    @Test
    @DisplayName("Chỉ tải dữ liệu cho đúng 1 mã yêu cầu và cache 24h ngăn gọi lại Alpha Vantage")
    public void testFetchAndCacheStock_cachesFor24Hours() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(ALPHA_VANTAGE_SAMPLE_JSON);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        // Lần 1: Gọi fetch cho AAPL -> Gọi HTTP
        StockDetailDto detail1 = stockMarketService.getStockDetail("AAPL");
        assertNotNull(detail1);
        assertEquals("AAPL", detail1.symbol());
        assertEquals(new BigDecimal("224.25"), detail1.currentPrice());
        assertEquals("2026-09-14", detail1.priceAsOf());
        assertFalse(detail1.stale(), "Cache trong TTL 24h phải có stale=false");

        // Lần 2: Gọi lại ngay cho AAPL -> Phải lấy từ Cache 24h, không gọi HTTP lần 2
        StockDetailDto detail2 = stockMarketService.getStockDetail("AAPL");
        assertNotNull(detail2);
        assertEquals("AAPL", detail2.symbol());
        assertEquals(new BigDecimal("224.25"), detail2.currentPrice());
        assertFalse(detail2.stale());

        // HttpClient.send chỉ được gọi đúng 1 lần cho Alpha Vantage
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());
    }

    // =========================================================================
    // 3. ZERO FAKE & POLICY KHI PROVIDER LỖI + COOLDOWN
    // =========================================================================

    @Test
    @DisplayName("Nếu provider lỗi và có cache cũ, trả cache với stale=true; nếu chưa có cache, ném 503 MarketDataUnavailableException")
    public void testProviderFailure_fallbackPolicy() throws Exception {
        // Trường hợp A: Chưa có cache và Provider trả lỗi 500 -> kích hoạt cooldown 15p và ném 503
        HttpResponse<String> errorResponse = mock(HttpResponse.class);
        when(errorResponse.statusCode()).thenReturn(500);
        doReturn(errorResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.getStockDetail("MSFT"));
        assertTrue(stockMarketService.getCooldownUntil("MSFT") > System.currentTimeMillis());

        // Trường hợp B: Đã có cache sẵn trong hệ thống, sau đó provider sập -> Trả cache với stale=true
        StockMarketService.CachedStockData existingCache = new StockMarketService.CachedStockData(
                "MSFT",
                "Microsoft Corporation",
                new BigDecimal("420.50"),
                new BigDecimal("1.25"),
                "2026-09-14",
                List.of(new CandleDto("2026-09-14", new BigDecimal("415"), new BigDecimal("422"), new BigDecimal("414"), new BigDecimal("420.50"), BigDecimal.ZERO)),
                System.currentTimeMillis() - (25 * 60 * 60 * 1000L), // Quá 24h
                false
        );
        stockMarketService.putInCache(existingCache);
        stockMarketService.clearCache(); // clear cooldown
        stockMarketService.putInCache(existingCache);

        StockDetailDto staleResult = stockMarketService.getStockDetail("MSFT");
        assertNotNull(staleResult);
        assertEquals("MSFT", staleResult.symbol());
        assertEquals(new BigDecimal("420.50"), staleResult.currentPrice());
        assertTrue(staleResult.stale(), "Phải đánh dấu stale=true khi provider lỗi và dùng cache cũ");
    }

    // =========================================================================
    // 4. COOLDOWN THEO SYMBOL: LỖI THƯỜNG 15 PHÚT, 429 HOẶC NOTE/INFO 24 GIỜ
    // =========================================================================

    @Test
    @DisplayName("HTTP 429 kích hoạt cooldown 24 giờ và không gọi lại Alpha trong thời gian cooldown")
    public void testCooldown_http429Triggers24HoursCooldown() throws Exception {
        HttpResponse<String> rateLimitResponse = mock(HttpResponse.class);
        when(rateLimitResponse.statusCode()).thenReturn(429);
        doReturn(rateLimitResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        long beforeCall = System.currentTimeMillis();
        assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("TSLA"));

        Long cooldownUntil = stockMarketService.getCooldownUntil("TSLA");
        assertNotNull(cooldownUntil);
        assertTrue(cooldownUntil >= beforeCall + StockMarketService.RATE_LIMIT_COOLDOWN_MS - 1000);

        // Gọi lại ngay trong cooldown -> không gọi HTTP lần 2
        assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("TSLA"));
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("Alpha Vantage Note/Information kích hoạt cooldown 24 giờ")
    public void testCooldown_noteTriggers24HoursCooldown() throws Exception {
        HttpResponse<String> noteResponse = mock(HttpResponse.class);
        when(noteResponse.statusCode()).thenReturn(200);
        when(noteResponse.body()).thenReturn("""
                {
                    "Note": "Thank you for using Alpha Vantage! Our standard API call frequency is 25 requests per day."
                }
                """);
        doReturn(noteResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        long beforeCall = System.currentTimeMillis();
        assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("NVDA"));

        Long cooldownUntil = stockMarketService.getCooldownUntil("NVDA");
        assertNotNull(cooldownUntil);
        assertTrue(cooldownUntil >= beforeCall + StockMarketService.RATE_LIMIT_COOLDOWN_MS - 1000);
    }

    // =========================================================================
    // 5. GEMINI CHỈ CHẤP NHẬN CHÍNH XÁC "Nên cân nhắc mua" HOẶC "Chưa nên mua"
    // =========================================================================

    @Test
    @DisplayName("Gemini trả nhãn không hợp lệ hoặc sai định dạng phải trả null, không tự gán 'Chưa nên mua'")
    public void testGeminiRecommendation_strictExactMatching() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(stockMarketService, "geminiApiKey", "test-gemini-key");

        // Mock Alpha Vantage nạp thành công
        HttpResponse<String> alphaResp = mock(HttpResponse.class);
        when(alphaResp.statusCode()).thenReturn(200);
        when(alphaResp.body()).thenReturn(ALPHA_VANTAGE_SAMPLE_JSON);

        // Mock Gemini trả nhãn "HOLD" (không phải 2 nhãn chuẩn)
        HttpResponse<String> geminiResp = mock(HttpResponse.class);
        when(geminiResp.statusCode()).thenReturn(200);
        when(geminiResp.body()).thenReturn("""
                {
                    "choices": [
                        {
                            "message": {
                                "content": "{\\"recommendation\\": \\"HOLD\\"}"
                            }
                        }
                    ]
                }
                """);

        doReturn(alphaResp).doReturn(geminiResp).when(mockHttpClient).send(any(HttpRequest.class), any());

        StockDetailDto detail = stockMarketService.getStockDetail("AMZN");
        assertNotNull(detail);
        assertNull(detail.recommendation(), "Nhãn sai phải trả null, tuyệt đối không tự gán 'Chưa nên mua'");
    }

    // =========================================================================
    // 6. TRADESERVICE TỪ CHỐI MỌI MARKETPRICEDTO CÓ STALE=TRUE
    // =========================================================================

    @Test
    @DisplayName("TradeService từ chối mọi MarketPriceDto có stale=true kể cả cổ phiếu")
    public void testTradeService_rejectsStalePriceUnconditionally() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);

        MarketPriceDto staleStockPrice = new MarketPriceDto(
                "AAPL", "Apple Inc.", "STOCK", new BigDecimal("220.00"),
                BigDecimal.ZERO, new BigDecimal("220.00"), new BigDecimal("220.00"),
                "2026-09-14", true, "CACHE_ALPHA_VANTAGE", "2026-09-14"
        );

        OrderRequest buyOrder = new OrderRequest("AAPL", "BUY", new BigDecimal("5"), "uuid-order-aapl-stale");
        assertThrows(MarketDataUnavailableException.class, () ->
                tradeService.executeOrder(10L, buyOrder, staleStockPrice));
    }

    @Test
    @DisplayName("TradeService cho phép mua cổ phiếu khi giá fresh (stale=false)")
    public void testTradeService_executesStockOrderWithFreshPrice() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Long userId = 10L;
        Wallet wallet = new Wallet(userId, new BigDecimal("10000.0000"));
        wallet.setId(100L);

        when(mockWalletRepo.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(mockWalletRepo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(mockTxRepo.findByWalletIdAndClientOrderId(100L, "uuid-order-aapl-fresh")).thenReturn(Optional.empty());
        when(mockHoldingRepo.findByWalletIdAndSymbolForUpdate(100L, "AAPL")).thenReturn(Optional.empty());
        when(mockTxRepo.saveAndFlush(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);

        // Giá cổ phiếu AAPL tươi với stale=false
        MarketPriceDto freshStockPrice = new MarketPriceDto(
                "AAPL", "Apple Inc.", "STOCK", new BigDecimal("220.00"),
                BigDecimal.ZERO, new BigDecimal("220.00"), new BigDecimal("220.00"),
                "2026-09-14", false, "ALPHA_VANTAGE", "2026-09-14"
        );

        OrderRequest buyOrder = new OrderRequest("AAPL", "BUY", new BigDecimal("5"), "uuid-order-aapl-fresh");
        OrderResponse response = tradeService.executeOrder(userId, buyOrder, freshStockPrice);

        assertNotNull(response);
        assertEquals("AAPL", response.getSymbol());
        assertEquals("BUY", response.getType());
        assertEquals(new BigDecimal("5"), response.getQuantity());
        assertEquals(new BigDecimal("220.00"), response.getPrice());
        assertEquals(new BigDecimal("1100.0000"), response.getTotalAmount());
        assertEquals(new BigDecimal("8900.0000"), response.getRemainingBalance());
    }

    // =========================================================================
    // 7. PORTFOLIO ĐỊNH GIÁ CHƯA ĐẦY ĐỦ KHI THIẾU GIÁ THỊ TRƯỜNG
    // =========================================================================

    @Test
    @DisplayName("Khi holding thiếu giá thị trường, không dùng avgBuyPrice; trả định giá null và fullyValued=false")
    public void testPortfolioSummary_unvaluedHoldingHandledCorrectly() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Long userId = 10L;
        Wallet wallet = new Wallet(userId, new BigDecimal("5000.0000"));
        wallet.setId(100L);

        Holding holding = new Holding();
        holding.setId(1L);
        holding.setWalletId(100L);
        holding.setSymbol("AAPL");
        holding.setQuantity(new BigDecimal("10"));
        holding.setAvgBuyPrice(new BigDecimal("200.00"));
        holding.setUpdatedAt(LocalDateTime.now());

        when(mockWalletRepo.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(mockHoldingRepo.findByWalletId(100L)).thenReturn(List.of(holding));

        // Giả lập provider thiếu giá thị trường
        when(mockMarketData.getPriceBySymbol("AAPL")).thenThrow(new MarketDataUnavailableException("Provider down"));

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);
        PortfolioSummaryDto summary = tradeService.getPortfolioSummary(userId);

        assertNotNull(summary);
        assertFalse(summary.isFullyValued(), "Portfolio phải được đánh dấu fullyValued=false");
        assertNull(summary.getTotalHoldingsValue());
        assertNull(summary.getTotalNetWorth());
        assertNull(summary.getTotalPnL());
        assertNull(summary.getTotalPnLPercent());

        assertEquals(1, summary.getHoldings().size());
        HoldingDto holdingDto = summary.getHoldings().get(0);
        assertNull(holdingDto.getCurrentPrice(), "Không được dùng avgBuyPrice làm currentPrice");
        assertNull(holdingDto.getCurrentValue());
        assertNull(holdingDto.getUnrealizedPnL());
        assertNull(holdingDto.getPnlPercent());
    }

    // =========================================================================
    // 8. WATCHLIST: ĐỌC TỪ CACHE (KHÔNG BULK FETCH), FETCH KHI ADD
    // =========================================================================

    @Test
    @DisplayName("Watchlist đọc từ cache không gọi bulk provider; một mã chưa cache trả null giá chứ không làm hỏng list")
    public void testWatchlistService_stockIntegration() {
        WatchlistRepository mockWatchlistRepo = mock(WatchlistRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);
        ForecastService mockForecast = mock(ForecastService.class);
        AiNewsService mockAiNews = mock(AiNewsService.class);

        WatchlistService watchlistService = new WatchlistService(
                mockWatchlistRepo, mockMarketData, mockForecast, mockAiNews, stockMarketService);

        Watchlist w1 = new Watchlist();
        w1.setId(1L);
        w1.setUserId(10L);
        w1.setSymbol("AAPL");
        w1.setDisplayOrder(1);
        w1.setCreatedAt(LocalDateTime.now());

        Watchlist w2 = new Watchlist();
        w2.setId(2L);
        w2.setUserId(10L);
        w2.setSymbol("NVDA");
        w2.setDisplayOrder(2);
        w2.setCreatedAt(LocalDateTime.now());

        when(mockWatchlistRepo.findByUserIdOrderByDisplayOrderAsc(10L)).thenReturn(List.of(w1, w2));

        stockMarketService.putInCache(new StockMarketService.CachedStockData(
                "AAPL", "Apple Inc.", new BigDecimal("224.25"), new BigDecimal("1.50"),
                "2026-09-14", List.of(), System.currentTimeMillis(), false
        ));

        List<WatchlistItemDto> items = watchlistService.getUserWatchlist(10L);
        assertEquals(2, items.size());

        WatchlistItemDto aaplItem = items.get(0);
        assertEquals("AAPL", aaplItem.getSymbol());
        assertEquals(new BigDecimal("224.25"), aaplItem.getCurrentPrice());
        assertEquals("2026-09-14", aaplItem.getPriceAsOf());
        assertFalse(aaplItem.getStale());

        WatchlistItemDto nvdaItem = items.get(1);
        assertEquals("NVDA", nvdaItem.getSymbol());
        assertNull(nvdaItem.getCurrentPrice());
        assertNull(nvdaItem.getPriceAsOf());
        assertFalse(nvdaItem.getStale());
    }

    // =========================================================================
    // 9. TỪ CHỐI MÃ KHÔNG HỖ TRỢ (HTTP 422 UNSUPPORTED SYMBOL)
    // =========================================================================

    @Test
    @DisplayName("Từ chối các mã ngoài 8 cổ phiếu hỗ trợ và quăng UnsupportedSymbolException")
    public void testUnsupportedSymbols_throw422Exception() {
        assertFalse(MarketSymbolConfig.isSupported("XYZ"));
        assertFalse(MarketSymbolConfig.isSupported("BABA"));
        assertFalse(MarketSymbolConfig.isSupported("NFLX"));

        assertThrows(UnsupportedSymbolException.class, () -> stockMarketService.fetchAndCacheStock("XYZ"));
        assertThrows(UnsupportedSymbolException.class, () -> stockMarketService.getStockDetail("BABA"));
    }

    // =========================================================================
    // 10. KHUYẾN NGHỊ & TIN TỨC THẬT
    // =========================================================================

    @Test
    @DisplayName("StockDetail lấy đúng bài báo thực tế mới nhất từ NewsAiCacheRepository")
    public void testLatestReport_retrievedFromRealNewsDb() {
        NewsAiCache news = new NewsAiCache();
        news.setSymbol("AAPL");
        news.setTitle("Apple ra mắt thế hệ chip M4 mới");
        news.setArticleUrl("https://news.example.com/apple-m4");
        news.setPublishedAt(LocalDateTime.now());

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc("AAPL")).thenReturn(List.of(news));

        StockMarketService.CachedStockData data = new StockMarketService.CachedStockData(
                "AAPL", "Apple Inc.", new BigDecimal("224.25"), BigDecimal.ZERO,
                "2026-09-14", List.of(), System.currentTimeMillis(), false
        );
        stockMarketService.putInCache(data);

        StockDetailDto detail = stockMarketService.getStockDetail("AAPL");
        assertEquals("Apple ra mắt thế hệ chip M4 mới", detail.latestReportTitle());
        assertEquals("https://news.example.com/apple-m4", detail.latestReportUrl());
    }

    // =========================================================================
    // 11. XÁC THỰC DỮ LIỆU NẾN & PARSEDECIMAL KHÔNG TRẢ ZERO KHI DỮ LIỆU SAI
    // =========================================================================

    @Test
    @DisplayName("parseDecimal ném IllegalArgumentException khi chuỗi rỗng/sai định dạng; không trả BigDecimal.ZERO")
    public void testParseDecimal_strictValidation() {
        assertThrows(IllegalArgumentException.class, () -> stockMarketService.parseDecimal(null));
        assertThrows(IllegalArgumentException.class, () -> stockMarketService.parseDecimal(""));
        assertThrows(IllegalArgumentException.class, () -> stockMarketService.parseDecimal("   "));
        assertThrows(IllegalArgumentException.class, () -> stockMarketService.parseDecimal("abc"));
        assertThrows(IllegalArgumentException.class, () -> stockMarketService.parseDecimal("NaN"));

        assertEquals(new BigDecimal("123.45"), stockMarketService.parseDecimal("123.45"));
        assertEquals(new BigDecimal("0.00"), stockMarketService.parseDecimal("0.00"));
    }

    @Test
    @DisplayName("Dữ liệu nến Alpha sai (giá <= 0, high < low, high < open/close, low > open/close) kích hoạt cooldown 15m và không cache")
    public void testCandleValidation_corruptDataTriggersCooldownAndNotCached() throws Exception {
        // Dữ liệu có high (100.0) nhỏ hơn low (120.0)
        String corruptJsonHighLowerThanLow = """
                {
                    "Time Series (Daily)": {
                        "2026-09-14": {
                            "1. open": "110.0000",
                            "2. high": "100.0000",
                            "3. low": "120.0000",
                            "4. close": "115.0000",
                            "5. volume": "10000"
                        }
                    }
                }
                """;

        HttpResponse<String> badResponse = mock(HttpResponse.class);
        when(badResponse.statusCode()).thenReturn(200);
        when(badResponse.body()).thenReturn(corruptJsonHighLowerThanLow);
        doReturn(badResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        long beforeCall = System.currentTimeMillis();
        assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("TSLA"));

        // Khẳng định kích hoạt cooldown 15 phút
        Long cooldownUntil = stockMarketService.getCooldownUntil("TSLA");
        assertNotNull(cooldownUntil);
        assertTrue(cooldownUntil >= beforeCall + StockMarketService.GENERAL_ERROR_COOLDOWN_MS - 1000);

        // Khẳng định KHÔNG cache dữ liệu sai vào RAM
        assertNull(stockMarketService.getCachedStock("TSLA"));

        // Dữ liệu có giá <= 0 ("close": "-5.0000")
        String corruptJsonNegativePrice = """
                {
                    "Time Series (Daily)": {
                        "2026-09-14": {
                            "1. open": "110.0000",
                            "2. high": "125.0000",
                            "3. low": "105.0000",
                            "4. close": "-5.0000",
                            "5. volume": "10000"
                        }
                    }
                }
                """;
        stockMarketService.clearCache();
        HttpResponse<String> negativeResponse = mock(HttpResponse.class);
        when(negativeResponse.statusCode()).thenReturn(200);
        when(negativeResponse.body()).thenReturn(corruptJsonNegativePrice);
        doReturn(negativeResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("TSLA"));
        assertNotNull(stockMarketService.getCooldownUntil("TSLA"));
        assertNull(stockMarketService.getCachedStock("TSLA"));
    }

    // =========================================================================
    // 12. LOG-CAPTURE BEHAVIORAL TEST: BẢO MẬT API KEY & HTTP BODY
    // =========================================================================

    @Test
    @DisplayName("Log-capture: API key giả trong Note, Information, Error Message, HTTP error body và exception không bao giờ xuất hiện trong log hoặc exception")
    public void testLogCapture_apiKeyAndBodyNeverLeakedInLogsOrExceptions() throws Exception {
        Logger stockLogger = (Logger) LoggerFactory.getLogger(StockMarketService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        stockLogger.addAppender(listAppender);

        final String fakeSecretKey = "MOCK_SECRET_API_KEY_XYZZY_998877";
        final String sensitiveErrorBody = "SENSITIVE_LEAK_BODY_CONTENT_12345";
        org.springframework.test.util.ReflectionTestUtils.setField(stockMarketService, "alphaVantageKey", fakeSecretKey);

        try {
            // Trường hợp A: Note chứa fake API key
            stockMarketService.clearCache();
            HttpResponse<String> respNote = mock(HttpResponse.class);
            when(respNote.statusCode()).thenReturn(200);
            when(respNote.body()).thenReturn("{\"Note\": \"Rate limit reached for key " + fakeSecretKey + "\"}");
            doReturn(respNote).when(mockHttpClient).send(any(HttpRequest.class), any());

            assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("AAPL"));

            // Trường hợp B: Information chứa fake API key
            stockMarketService.clearCache();
            HttpResponse<String> respInfo = mock(HttpResponse.class);
            when(respInfo.statusCode()).thenReturn(200);
            when(respInfo.body()).thenReturn("{\"Information\": \"Standard call frequency limit reached for " + fakeSecretKey + "\"}");
            doReturn(respInfo).when(mockHttpClient).send(any(HttpRequest.class), any());

            assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("MSFT"));

            // Trường hợp C: Error Message chứa fake API key
            stockMarketService.clearCache();
            HttpResponse<String> respErr = mock(HttpResponse.class);
            when(respErr.statusCode()).thenReturn(200);
            when(respErr.body()).thenReturn("{\"Error Message\": \"Invalid API call for apikey " + fakeSecretKey + "\"}");
            doReturn(respErr).when(mockHttpClient).send(any(HttpRequest.class), any());

            assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("NVDA"));

            // Trường hợp D: HTTP 500 error body chứa fake key và payload nhạy cảm
            stockMarketService.clearCache();
            HttpResponse<String> respHttpError = mock(HttpResponse.class);
            when(respHttpError.statusCode()).thenReturn(500);
            when(respHttpError.body()).thenReturn("{\"error\": \"Crash with key " + fakeSecretKey + "\", \"payload\": \"" + sensitiveErrorBody + "\"}");
            doReturn(respHttpError).when(mockHttpClient).send(any(HttpRequest.class), any());

            assertThrows(MarketDataUnavailableException.class, () -> stockMarketService.fetchAndCacheStock("AMZN"));

            // Trường hợp E: Exception ném ra từ HttpClient có URL chứa fake key
            stockMarketService.clearCache();
            doThrow(new IOException("Connect timed out to https://www.alphavantage.co/query?apikey=" + fakeSecretKey))
                    .when(mockHttpClient).send(any(HttpRequest.class), any());

            MarketDataUnavailableException ex = assertThrows(MarketDataUnavailableException.class,
                    () -> stockMarketService.fetchAndCacheStock("GOOGL"));

            // Khẳng định exception message trả ra người dùng không chứa fake key hoặc sensitive body
            assertFalse(ex.getMessage().contains(fakeSecretKey));
            assertFalse(ex.getMessage().contains(sensitiveErrorBody));

            // KHẲNG ĐỊNH TẤT CẢ CÁC DÒNG LOG KHÔNG CHỨA FAKE KEY HAY SENSITIVE BODY
            List<ILoggingEvent> logsList = listAppender.list;
            assertFalse(logsList.isEmpty(), "Phải có các sự kiện log được ghi nhận");

            for (ILoggingEvent event : logsList) {
                String message = event.getFormattedMessage();
                assertFalse(message.contains(fakeSecretKey),
                        "Phát hiện rò rỉ API key trong log: " + message);
                assertFalse(message.contains(sensitiveErrorBody),
                        "Phát hiện rò rỉ HTTP error body trong log: " + message);
            }
        } finally {
            stockLogger.detachAppender(listAppender);
        }
    }

    // =========================================================================
    // 13. KHUYẾN NGHỊ GEMINI: TÍCH HỢP BÀI BÁO THẬT TIẾNG VIỆT TỪ NEWS_AI_CACHE
    // =========================================================================

    @Test
    @DisplayName("Gemini prompt nhận thêm bài báo thật mới nhất nếu có tiêu đề/tóm tắt tiếng Việt hợp lệ; không tạo tin giả")
    public void testGeminiPrompt_realVietnameseNewsIntegration() {
        CandleDto c1 = new CandleDto("2026-09-14", new BigDecimal("220.00"), new BigDecimal("225.50"),
                new BigDecimal("219.00"), new BigDecimal("224.25"), new BigDecimal("50000000"));
        List<CandleDto> candles = List.of(c1);

        // 1. Có bài báo tiếng Việt hợp lệ trong NEWS_AI_CACHE
        NewsAiCache newsVi = new NewsAiCache();
        newsVi.setSymbol("AAPL");
        newsVi.setDisplayTitleVi("Apple ra mắt thế hệ chip M4 mới với hiệu năng AI vượt trội");
        newsVi.setDisplaySummaryVi("Doanh thu quý tăng trưởng mạnh nhờ các dòng sản phẩm mới");
        newsVi.setArticleUrl("https://news.example.com/apple-m4");

        String promptWithViNews = stockMarketService.buildGeminiUserPrompt("AAPL", "Apple Inc.",
                new BigDecimal("224.25"), candles, newsVi);

        assertTrue(promptWithViNews.contains("Apple ra mắt thế hệ chip M4 mới với hiệu năng AI vượt trội"));
        assertTrue(promptWithViNews.contains("Doanh thu quý tăng trưởng mạnh nhờ các dòng sản phẩm mới"));
        assertTrue(promptWithViNews.contains("Thông tin tin tức thị trường thực tế:"));

        // 2. Có bài báo tiếng Anh nhưng không có tiếng Việt -> không đưa vào prompt, không sinh tin giả
        NewsAiCache newsEnOnly = new NewsAiCache();
        newsEnOnly.setSymbol("AAPL");
        newsEnOnly.setTitle("Apple introduces M4 chip family with breakthrough AI performance");
        newsEnOnly.setOriginalSummary("Quarterly revenues beat estimates on robust hardware sales");
        newsEnOnly.setArticleUrl("https://news.example.com/apple-m4-en");

        String promptWithEnOnly = stockMarketService.buildGeminiUserPrompt("AAPL", "Apple Inc.",
                new BigDecimal("224.25"), candles, newsEnOnly);

        assertFalse(promptWithEnOnly.contains("Apple introduces M4 chip family"));
        assertFalse(promptWithEnOnly.contains("Quarterly revenues beat estimates"));
        assertFalse(promptWithEnOnly.contains("Thông tin tin tức thị trường thực tế:"));
        assertTrue(promptWithEnOnly.contains("Giá hiện tại: $224.25"));

        // 3. Không có bài báo nào (null) -> phân tích nến và không tạo tin giả
        String promptWithoutNews = stockMarketService.buildGeminiUserPrompt("AAPL", "Apple Inc.",
                new BigDecimal("224.25"), candles, null);

        assertFalse(promptWithoutNews.contains("Thông tin tin tức thị trường thực tế:"));
        assertTrue(promptWithoutNews.contains("Giá hiện tại: $224.25"));
        assertTrue(promptWithoutNews.contains("Ngày 2026-09-14: Đóng=224.25"));
    }
}
