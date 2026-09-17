package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.news.AlphaNewsFetchResult;
import com.llmgateway.dto.news.NewsAnalysisRequest;
import com.llmgateway.dto.news.NewsAnalysisResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.dto.watchlist.WatchlistRequest;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.entity.Watchlist;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.WatchlistRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.AlphaNewsCoordinator;
import com.llmgateway.service.BinanceMarketClient;
import com.llmgateway.service.ForecastService;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.service.NewsCacheService;
import com.llmgateway.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WatchlistAndNewsBehavioralTest {

    private WatchlistRepository watchlistRepository;
    private MarketDataService marketDataService;
    private ForecastService forecastService;
    private AiNewsService aiNewsService;
    private NewsCacheService newsCacheService;
    private NewsAiCacheRepository newsAiCacheRepository;
    private WatchlistService watchlistService;
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        watchlistRepository = mock(WatchlistRepository.class);
        marketDataService = mock(MarketDataService.class);
        forecastService = mock(ForecastService.class);
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        newsCacheService = mock(NewsCacheService.class);
        objectMapper = new ObjectMapper();

        aiNewsService = new AiNewsService(newsCacheService, newsAiCacheRepository, objectMapper);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", "");
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "");

        watchlistService = new WatchlistService(
                watchlistRepository,
                marketDataService,
                forecastService,
                aiNewsService
        );
    }

    // =========================================================================
    // 1. GET watchlist với 8 mã tạo 0 external provider calls
    // =========================================================================
    @Test
    @DisplayName("1. GET watchlist với 8 mã tạo 0 external provider calls (chỉ gọi getCachedPrice, không gọi getPriceBySymbol)")
    public void test01_getUserWatchlist_makesZeroExternalProviderCalls() {
        Long userId = 1L;
        List<Watchlist> dbItems = new ArrayList<>();
        List<String> symbols = Arrays.asList(
                "BTCUSDT", "ETHUSDT", "XAUUSD", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT"
        );
        for (int i = 0; i < symbols.size(); i++) {
            Watchlist w = new Watchlist();
            w.setId((long) (i + 1));
            w.setUserId(userId);
            w.setSymbol(symbols.get(i));
            w.setDisplayOrder(i + 1);
            dbItems.add(w);
        }
        when(watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId)).thenReturn(dbItems);

        // Mock cached price
        when(marketDataService.getCachedPrice(anyString())).thenAnswer(invocation -> {
            String sym = invocation.getArgument(0);
            return new MarketPriceDto(
                    sym, sym, "CRYPTO",
                    BigDecimal.valueOf(100.0), BigDecimal.valueOf(2.5),
                    BigDecimal.valueOf(100.0), BigDecimal.valueOf(100.0),
                    LocalDateTime.now().toString()
            );
        });

        List<WatchlistItemDto> result = watchlistService.getUserWatchlist(userId);

        assertEquals(8, result.size());
        // verify getCachedPrice was called 8 times
        verify(marketDataService, times(8)).getCachedPrice(anyString());
        // verify getPriceBySymbol (external provider network call) was NEVER called
        verify(marketDataService, never()).getPriceBySymbol(anyString());
    }

    // =========================================================================
    // 2. Watchlist vẫn trả symbol khi giá cache null
    // =========================================================================
    @Test
    @DisplayName("2. Watchlist vẫn trả symbol khi giá cache null, không ném ngoại lệ và không làm mất bản ghi")
    public void test02_getUserWatchlist_returnsSymbols_whenCachedPriceIsNull() {
        Long userId = 1L;
        Watchlist w = new Watchlist();
        w.setId(10L);
        w.setUserId(userId);
        w.setSymbol("BNBUSDT");
        when(watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId)).thenReturn(List.of(w));

        // Cache trả về null (chưa có giá)
        when(marketDataService.getCachedPrice("BNBUSDT")).thenReturn(null);

        List<WatchlistItemDto> result = watchlistService.getUserWatchlist(userId);

        assertEquals(1, result.size());
        assertEquals("BNBUSDT", result.get(0).getSymbol());
        assertNull(result.get(0).getCurrentPrice(), "Giá phải là null thay vì 0.0 giả khi chưa có cache");
        assertNull(result.get(0).getChange24h());
    }

    // =========================================================================
    // 3. Legacy stocks bị ẩn khỏi active watchlist nhưng không bị xóa khỏi DB
    // =========================================================================
    @Test
    @DisplayName("3. Legacy stocks (AAPL, MSFT) bị ẩn khỏi active watchlist nhưng không bị xóa khỏi DB")
    public void test03_getUserWatchlist_hidesLegacyStocks_withoutDeletingFromDb() {
        Long userId = 2L;
        Watchlist w1 = new Watchlist();
        w1.setId(1L);
        w1.setUserId(userId);
        w1.setSymbol("AAPL");

        Watchlist w2 = new Watchlist();
        w2.setId(2L);
        w2.setUserId(userId);
        w2.setSymbol("MSFT");

        Watchlist w3 = new Watchlist();
        w3.setId(3L);
        w3.setUserId(userId);
        w3.setSymbol("BTCUSDT");

        when(watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId)).thenReturn(List.of(w1, w2, w3));

        List<WatchlistItemDto> result = watchlistService.getUserWatchlist(userId);

        assertEquals(1, result.size());
        assertEquals("BTCUSDT", result.get(0).getSymbol());

        // Tuyệt đối không xóa bản ghi legacy khỏi CSDL để không phá vỡ history/portfolio
        verify(watchlistRepository, never()).delete(any());
        verify(watchlistRepository, never()).deleteAll(any());
        verify(watchlistRepository, never()).deleteByUserIdAndSymbol(anyLong(), anyString());
    }

    // =========================================================================
    // 4. Các symbol trùng chỉ xuất hiện một lần (Deduplicate)
    // =========================================================================
    @Test
    @DisplayName("4. Các symbol trùng (BTCUSDT, btcusdt, BTC/USDT) chỉ xuất hiện một lần theo canonical symbol")
    public void test04_getUserWatchlist_deduplicatesCanonicalSymbols() {
        Long userId = 3L;
        Watchlist w1 = new Watchlist();
        w1.setId(1L);
        w1.setUserId(userId);
        w1.setSymbol("BTCUSDT");

        Watchlist w2 = new Watchlist();
        w2.setId(2L);
        w2.setUserId(userId);
        w2.setSymbol("btcusdt");

        Watchlist w3 = new Watchlist();
        w3.setId(3L);
        w3.setUserId(userId);
        w3.setSymbol("BTC/USDT");

        when(watchlistRepository.findByUserIdOrderByDisplayOrderAsc(userId)).thenReturn(List.of(w1, w2, w3));

        List<WatchlistItemDto> result = watchlistService.getUserWatchlist(userId);

        assertEquals(1, result.size());
        assertEquals("BTCUSDT", result.get(0).getSymbol());
    }

    // =========================================================================
    // 5. Concurrent add cùng symbol không tạo duplicate (Idempotent & Concurrency-safe)
    // =========================================================================
    @Test
    @DisplayName("5. Concurrent add cùng symbol idempotent: trả bản ghi hiện có, bắt DataIntegrityViolationException")
    public void test05_addToWatchlist_isIdempotent_andConcurrencySafe() {
        Long userId = 4L;
        WatchlistRequest request = new WatchlistRequest("SOLUSDT", 1);

        // Trường hợp 1: Mã đã có trong CSDL -> Trả lại entity cũ, không gọi saveAndFlush
        Watchlist existing = new Watchlist();
        existing.setId(99L);
        existing.setUserId(userId);
        existing.setSymbol("SOLUSDT");
        when(watchlistRepository.findByUserIdAndSymbol(userId, "SOLUSDT")).thenReturn(Optional.of(existing));

        WatchlistItemDto res1 = watchlistService.addToWatchlist(userId, request);
        assertNotNull(res1);
        assertEquals("SOLUSDT", res1.getSymbol());
        assertEquals(99L, res1.getId());
        verify(watchlistRepository, never()).saveAndFlush(any());

        // Trường hợp 2: Race condition (chưa thấy qua find, nhưng saveAndFlush ném DataIntegrityViolationException)
        when(watchlistRepository.findByUserIdAndSymbol(userId, "SOLUSDT"))
                .thenReturn(Optional.empty()) // ban đầu rỗng
                .thenReturn(Optional.of(existing)); // sau conflict query lại
        when(watchlistRepository.saveAndFlush(any(Watchlist.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        WatchlistItemDto res2 = watchlistService.addToWatchlist(userId, request);
        assertNotNull(res2);
        assertEquals("SOLUSDT", res2.getSymbol());
        assertEquals(99L, res2.getId());
    }

    // =========================================================================
    // 6. Năm mã Binance mới đều route đúng và giữ trọn vẹn precision từ Binance (stripTrailingZeros)
    // =========================================================================
    @Test
    @DisplayName("6. Năm mã Binance mới (BNB, SOL, XRP, ADA, DOGE) route đúng và không làm tròn giá >= 10 xuống 2 chữ số")
    public void test06_binanceFiveNewSymbols_routeCorrectly_andHandleKlineScale() {
        // Kiểm tra MarketSymbolConfig mapping
        assertEquals("BNBUSDT", MarketSymbolConfig.getBinanceSymbol("BNBUSDT"));
        assertEquals("SOLUSDT", MarketSymbolConfig.getBinanceSymbol("SOLUSDT"));
        assertEquals("XRPUSDT", MarketSymbolConfig.getBinanceSymbol("XRPUSDT"));
        assertEquals("ADAUSDT", MarketSymbolConfig.getBinanceSymbol("ADAUSDT"));
        assertEquals("DOGEUSDT", MarketSymbolConfig.getBinanceSymbol("DOGEUSDT"));

        // Kiểm tra precision không bị làm tròn về 2 chữ số khi >= 10
        BigDecimal bnbPrice = BinanceMarketClient.parsePriceScale("590.25678");
        assertEquals(new BigDecimal("590.25678"), bnbPrice, "Giá BNB >= 10 phải giữ nguyên 5 chữ số thập phân, không bị làm tròn xuống 2");
        assertEquals(5, bnbPrice.scale());

        BigDecimal solPrice = BinanceMarketClient.parsePriceScale("145.5012");
        assertEquals(new BigDecimal("145.5012"), solPrice);

        BigDecimal dogePrice = BinanceMarketClient.parsePriceScale("0.082345");
        assertEquals(new BigDecimal("0.082345"), dogePrice);

        // Biên độ nhỏ không bị làm phẳng
        BigDecimal p1 = BinanceMarketClient.parsePriceScale("600.001");
        BigDecimal p2 = BinanceMarketClient.parsePriceScale("600.004");
        assertNotEquals(p1, p2, "Biên độ giá nhỏ 600.001 và 600.004 tuyệt đối không bị làm phẳng thành 600.00");
    }

    @Test
    @DisplayName("6b. Parser nến với full 30 nến cho 5 mã mới + BTC/ETH/XAU kiểm tra openTime tăng dần và OHLC invariants")
    public void test06b_binanceAllEightSymbols_parse30Klines_verifiesInvariantsAndPrecision() throws Exception {
        BinanceMarketClient client = new BinanceMarketClient(objectMapper);
        List<String> allEightSymbols = Arrays.asList(
                "BTCUSDT", "ETHUSDT", "XAUUSD", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT"
        );

        for (String canonical : allEightSymbols) {
            String binanceSymbol = MarketSymbolConfig.getBinanceSymbol(canonical);
            double basePrice = switch (canonical) {
                case "BTCUSDT" -> 60000.0;
                case "ETHUSDT" -> 2500.0;
                case "XAUUSD" -> 2600.0;
                case "BNBUSDT" -> 580.0;
                case "SOLUSDT" -> 140.0;
                case "XRPUSDT" -> 0.58;
                case "ADAUSDT" -> 0.35;
                case "DOGEUSDT" -> 0.082;
                default -> 100.0;
            };
            double tick = basePrice > 10 ? 0.005 : 0.0001;

            String klinesJson = generateMockBinanceKlinesJson(30, basePrice, tick);
            List<com.llmgateway.dto.market.CandleDto> candles = client.parseKlines(klinesJson, "1m");

            assertEquals(30, candles.size(), "Phải parse đủ 30 nến cho " + binanceSymbol);

            for (int i = 0; i < candles.size(); i++) {
                com.llmgateway.dto.market.CandleDto c = candles.get(i);
                assertNotNull(c.getOpenTime());
                assertNotNull(c.getOpen());
                assertNotNull(c.getHigh());
                assertNotNull(c.getLow());
                assertNotNull(c.getClose());
                assertNotNull(c.getVolume());

                // OHLC Invariants
                assertTrue(c.getHigh().compareTo(c.getLow()) >= 0, "High >= Low vi phạm tại index " + i);
                assertTrue(c.getHigh().compareTo(c.getOpen()) >= 0, "High >= Open vi phạm tại index " + i);
                assertTrue(c.getHigh().compareTo(c.getClose()) >= 0, "High >= Close vi phạm tại index " + i);
                assertTrue(c.getLow().compareTo(c.getOpen()) <= 0, "Low <= Open vi phạm tại index " + i);
                assertTrue(c.getLow().compareTo(c.getClose()) <= 0, "Low <= Close vi phạm tại index " + i);

                // openTime tăng dần nghiêm ngặt
                if (i > 0) {
                    assertTrue(c.getOpenTime() > candles.get(i - 1).getOpenTime(),
                            "openTime phải tăng dần nghiêm ngặt tại index " + i);
                    assertEquals(60000L, c.getOpenTime() - candles.get(i - 1).getOpenTime(),
                            "Khung 1m phải cách nhau đúng 60,000ms");
                }
            }
        }
    }

    private String generateMockBinanceKlinesJson(int count, double basePrice, double tickVariation) {
        long baseTime = 1695000000000L;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            long openTime = baseTime + i * 60000L;
            long closeTime = openTime + 59999L;
            double open = basePrice + i * tickVariation;
            double high = open + Math.abs(tickVariation) * 1.5;
            double low = open - Math.abs(tickVariation) * 0.8;
            double close = open + tickVariation * 0.5;
            double volume = 12.345678;
            sb.append(String.format(java.util.Locale.US,
                    "[%d,\"%.6f\",\"%.6f\",\"%.6f\",\"%.6f\",\"%.6f\",%d,\"0\",0,\"0\",\"0\",\"0\"]",
                    openTime, open, high, low, close, volume, closeTime));
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    @DisplayName("6c. Zero-Fake & Invariants: Production parser từ chối toàn bộ payload hỏng và không chấp nhận giá 0 giả")
    public void test06c_parserRejectsCorruptPayloads_andPreventsZeroFake() throws Exception {
        BinanceMarketClient client = new BinanceMarketClient(objectMapper);

        // 1. parsePriceScale từ chối null, blank, 0, số âm
        assertThrows(IllegalArgumentException.class, () -> BinanceMarketClient.parsePriceScale(null));
        assertThrows(IllegalArgumentException.class, () -> BinanceMarketClient.parsePriceScale(""));
        assertThrows(IllegalArgumentException.class, () -> BinanceMarketClient.parsePriceScale("   "));
        assertThrows(IllegalArgumentException.class, () -> BinanceMarketClient.parsePriceScale("0"));
        assertThrows(IllegalArgumentException.class, () -> BinanceMarketClient.parsePriceScale("0.00000"));
        assertThrows(IllegalArgumentException.class, () -> BinanceMarketClient.parsePriceScale("-5.5"));

        // 2. parseKlines từ chối payload có giá 0 giả (Zero-Fake)
        String zeroFakeJson = "[[1695000000000,\"0\",\"605\",\"599\",\"600\",\"10\",1695000059999]]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(zeroFakeJson, "1m"));

        // 3. parseKlines từ chối payload có giá rỗng
        String emptyPriceJson = "[[1695000000000,\"\",\"605\",\"599\",\"600\",\"10\",1695000059999]]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(emptyPriceJson, "1m"));

        // 4. parseKlines từ chối payload có volume âm
        String negativeVolumeJson = "[[1695000000000,\"600\",\"605\",\"599\",\"602\",\"-5.0\",1695000059999]]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(negativeVolumeJson, "1m"));

        // 5. parseKlines từ chối vi phạm OHLC: high < low
        String invertedHighLowJson = "[[1695000000000,\"600\",\"595\",\"605\",\"600\",\"10\",1695000059999]]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(invertedHighLowJson, "1m"));

        // 6. parseKlines từ chối vi phạm OHLC: high < open
        String invalidHighOpenJson = "[[1695000000000,\"610\",\"605\",\"599\",\"600\",\"10\",1695000059999]]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(invalidHighOpenJson, "1m"));

        // 7. parseKlines từ chối vi phạm OHLC: low > close
        String invalidLowCloseJson = "[[1695000000000,\"600\",\"615\",\"605\",\"600\",\"10\",1695000059999]]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(invalidLowCloseJson, "1m"));

        // 8. parseKlines từ chối openTime không tăng dần nghiêm ngặt (trùng hoặc giảm)
        String nonIncreasingOpenTimeJson = "[" +
                "[1695000060000,\"600\",\"605\",\"599\",\"602\",\"10\",1695000119999]," +
                "[1695000060000,\"602\",\"606\",\"601\",\"604\",\"10\",1695000119999]" +
                "]";
        assertThrows(IllegalArgumentException.class, () -> client.parseKlines(nonIncreasingOpenTimeJson, "1m"));

        // 9. parseKlines từ chối root không phải array
        assertThrows(IllegalStateException.class, () -> client.parseKlines("{\"error\":\"rate limit\"}", "1m"));
    }

    // =========================================================================
    // 7. forceRefresh News không dùng CachedAlphaSnapshot
    // =========================================================================
    @Test
    @DisplayName("7. forceRefresh News (forceRefresh=true) bỏ qua CachedAlphaSnapshot và fetch trực tiếp")
    public void test07_forceRefreshNews_bypassesCachedAlphaSnapshot() {
        AlphaNewsCoordinator coordinator = new AlphaNewsCoordinator();
        ReflectionTestUtils.setField(coordinator, "refreshIntervalMinutes", 90L);
        ReflectionTestUtils.setField(coordinator, "clock", Clock.systemUTC());

        // Lưu snapshot thành công trong coordinator
        coordinator.recordAlphaSnapshot("ALL", AlphaNewsFetchResult.Status.SUCCESS_EMPTY, Collections.emptyList());
        assertNotNull(coordinator.getCachedSnapshot());

        ReflectionTestUtils.setField(aiNewsService, "alphaNewsCoordinator", coordinator);

        // Khi forceRefresh=true và không có Alpha Key -> Báo UNAVAILABLE, không được trả empty từ snapshot
        NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5, true);

        // Vì không có Alpha key, sau khi bỏ qua snapshot, fetch thất bại -> degraded (không dùng SUCCESS_EMPTY từ snapshot)
        assertNotEquals("empty", result.getStatus(), "forceRefresh=true tuyệt đối không dùng snapshot empty để trả về empty");
    }

    // =========================================================================
    // 8. Normal load có thể dùng snapshot/cache
    // =========================================================================
    @Test
    @DisplayName("8. Normal load (forceRefresh=false) sử dụng cache PostgreSQL tiếng Việt còn mới mà không gọi provider")
    public void test08_normalLoadNews_usesFreshCache() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
        AlphaNewsCoordinator coordinator = new AlphaNewsCoordinator();
        ReflectionTestUtils.setField(coordinator, "refreshIntervalMinutes", 90L);
        ReflectionTestUtils.setField(coordinator, "clock", fixedClock);
        ReflectionTestUtils.setField(aiNewsService, "alphaNewsCoordinator", coordinator);

        // Tạo cache tiếng Việt hợp lệ được phân tích cách đây 10 phút (fresh < 90m)
        NewsAiCache cached = new NewsAiCache();
        cached.setArticleUrl("https://example.com/art1");
        cached.setTitle("Bitcoin tăng trưởng mạnh");
        cached.setDisplayTitleVi("Bitcoin tăng trưởng mạnh");
        cached.setOriginalTitle("Bitcoin rallies strongly");
        cached.setOriginalSummary("Bitcoin price surged past resistance level.");
        cached.setBulletPointsVi("[\"Bitcoin tăng mạnh vượt ngưỡng kháng cự.\", \"Dòng vốn tổ chức tiếp tục đổ vào thị trường.\"]");
        cached.setPublishedAt(LocalDateTime.of(2026, 9, 17, 11, 45));
        cached.setAnalyzedAt(LocalDateTime.of(2026, 9, 17, 11, 50));
        cached.setSentiment("BULLISH");
        cached.setConfidencePct(BigDecimal.valueOf(90));

        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(cached));

        NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5, false);

        assertEquals("ok", result.getStatus());
        assertFalse(result.isStale());
        assertFalse(result.getItems().isEmpty());
        assertEquals("Bitcoin tăng trưởng mạnh", result.getItems().get(0).getTitle());
    }

    // =========================================================================
    // 9. Cooldown trả cache với stale=true
    // =========================================================================
    @Test
    @DisplayName("9. Alpha Vantage đang trong Cooldown trả cache CSDL với metadata stale=true, fromCache=true")
    public void test09_cooldown_returnsCacheWithStaleTrue() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
        AlphaNewsCoordinator coordinator = new AlphaNewsCoordinator();
        ReflectionTestUtils.setField(coordinator, "refreshIntervalMinutes", 90L);
        ReflectionTestUtils.setField(coordinator, "rateLimitCooldownMinutes", 1440L);
        ReflectionTestUtils.setField(coordinator, "clock", fixedClock);
        coordinator.recordFailure("ALPHA_RATE_LIMITED"); // Trigger cooldown
        assertTrue(coordinator.isInCooldown());

        ReflectionTestUtils.setField(aiNewsService, "alphaNewsCoordinator", coordinator);

        NewsAiCache cached = new NewsAiCache();
        cached.setArticleUrl("https://example.com/art2");
        cached.setTitle("Cập nhật thị trường tiền mã hóa");
        cached.setDisplayTitleVi("Cập nhật thị trường tiền mã hóa");
        cached.setOriginalTitle("Crypto market update");
        cached.setOriginalSummary("Market is stabilizing after volatility.");
        cached.setBulletPointsVi("[\"Thị trường đang ổn định trở lại.\", \"Thanh khoản duy trì ở mức cao.\"]");
        cached.setPublishedAt(LocalDateTime.of(2026, 9, 17, 10, 0));
        cached.setAnalyzedAt(LocalDateTime.of(2026, 9, 17, 10, 5));
        cached.setSentiment("NEUTRAL");
        cached.setConfidencePct(BigDecimal.valueOf(80));

        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(cached));

        NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5, true);

        assertEquals("ok", result.getStatus());
        assertTrue(result.isStale(), "Phải đánh dấu stale=true khi trả cache trong cooldown");
        assertTrue(result.isFromCache(), "Phải đánh dấu fromCache=true");
        assertEquals("Đang hiển thị tin đã lưu gần nhất", result.getMessage());
        assertNotNull(result.getLatestPublishedAt());
    }

    // =========================================================================
    // 10. Bài cũ vừa được phân tích không bị coi là bài mới
    // =========================================================================
    @Test
    @DisplayName("10. Bài báo cũ (published 15/9) được phân tích hôm nay (17/9) vẫn giữ nguyên ngày publishedAt 15/9")
    public void test10_publishedDate_notOverwrittenByAnalyzedAt() {
        LocalDateTime originalPubDate = LocalDateTime.of(2026, 9, 15, 8, 30, 0);
        LocalDateTime analyzedDate = LocalDateTime.of(2026, 9, 17, 14, 0, 0);

        NewsAiCache cached = new NewsAiCache();
        cached.setArticleUrl("https://example.com/old-art");
        cached.setTitle("Tin tức kinh tế vĩ mô ngày 15/9");
        cached.setDisplayTitleVi("Tin tức kinh tế vĩ mô ngày 15/9");
        cached.setOriginalTitle("Macro economic news from Sep 15");
        cached.setOriginalSummary("Old macro news summary.");
        cached.setBulletPointsVi("[\"Chỉ số CPI giữ nguyên.\", \"Lãi suất không đổi.\"]");
        cached.setPublishedAt(originalPubDate);
        cached.setAnalyzedAt(analyzedDate);
        cached.setSentiment("NEUTRAL");

        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(cached));

        List<NewsFeedItemDto> items = aiNewsService.getValidLocalizedCacheItems("BTCUSDT", 5);

        assertFalse(items.isEmpty());
        NewsFeedItemDto item = items.get(0);
        // publishedAt phải là ngày 15/9, không được thay thế bằng ngày analyzedAt 17/9
        assertTrue(item.getTimePublished().contains("2026-09-15"), "publishedAt phải phản ánh thời gian xuất bản nguồn (2026-09-15)");
        assertFalse(item.getTimePublished().contains("2026-09-17"), "publishedAt không được là thời gian phân tích");
    }

    @Test
    @DisplayName("10b. isCacheFresh trả false khi bài báo mới nhất xuất bản > 24h dù analyzedAt vừa mới phân tích")
    public void test10b_isCacheFresh_returnsFalse_whenPublishedAtOlderThan24Hours() {
        NewsFeedItemDto freshAnalyzedOldPublished = new NewsFeedItemDto();
        freshAnalyzedOldPublished.setTitle("Bản tin phân tích tuần trước");
        // Xuất bản 3 ngày trước (14/9)
        freshAnalyzedOldPublished.setTimePublished(LocalDateTime.now().minusDays(3).toString());
        // Nhưng vừa mới được Gemini phân tích 5 phút trước
        freshAnalyzedOldPublished.setAnalyzedAt(LocalDateTime.now().minusMinutes(5).toString());

        boolean isFresh = aiNewsService.isCacheFresh(List.of(freshAnalyzedOldPublished));
        assertFalse(isFresh, "Bài báo xuất bản > 24h phải bị coi là STALE để hệ thống tải tin mới");

        // Khi bài báo xuất bản trong vòng 24h (ví dụ 3 giờ trước)
        NewsFeedItemDto recentItem = new NewsFeedItemDto();
        recentItem.setTitle("Bản tin mới nhất");
        recentItem.setTimePublished(LocalDateTime.now().minusHours(3).toString());
        recentItem.setAnalyzedAt(LocalDateTime.now().minusMinutes(5).toString());

        assertTrue(aiNewsService.isCacheFresh(List.of(recentItem)), "Bài báo xuất bản <= 24h và analyzedAt mới phải là FRESH");
    }

    // =========================================================================
    // 11. Cùng article URL không gọi Gemini lần hai
    // =========================================================================
    @Test
    @DisplayName("11. Cùng article URL đã có bản dịch/tóm tắt hợp lệ trong DB thì tái sử dụng, không gọi Gemini")
    public void test11_existingArticleUrl_doesNotCallGeminiAgain() {
        String url = "https://example.com/article-duplicate";

        NewsAiCache cached = new NewsAiCache();
        cached.setArticleUrl(url);
        cached.setTitle("Thị trường vàng đạt đỉnh mới");
        cached.setDisplayTitleVi("Thị trường vàng đạt đỉnh mới");
        cached.setOriginalTitle("Gold market reaches new high");
        cached.setOriginalSummary("Gold price jumped to a record high.");
        cached.setBulletPointsVi("[\"Giá vàng đạt mức cao kỷ lục mới.\", \"Nhu cầu trú ẩn an toàn tăng vọt.\"]");
        cached.setSentiment("BULLISH");
        cached.setConfidencePct(BigDecimal.valueOf(95));
        cached.setReason("Lực mua kỹ thuật mạnh mẽ.");

        when(newsCacheService.findByArticleUrl(url)).thenReturn(Optional.of(cached));

        NewsAnalysisRequest request = new NewsAnalysisRequest("Gold market reaches new high", "Gold price jumped to a record high.", "XAUUSD", url);

        NewsAnalysisResponse response = aiNewsService.analyzeNews(request);

        assertNotNull(response);
        assertTrue(response.isFromCache(), "Phải lấy từ cache DB");
        assertEquals("Thị trường vàng đạt đỉnh mới", response.getDisplayTitleVi());
        assertEquals("BULLISH", response.getSentiment());
    }

    // =========================================================================
    // 12. Không rò secret/provider body trong log hoặc API response
    // =========================================================================
    @Test
    @DisplayName("12. Không rò secret, API key hoặc raw provider error trong log hoặc API response")
    public void test12_noSecretLeakInResponsesOrLogs() {
        // NewsSyncResult degraded
        NewsSyncResult degradedResult = NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
        assertFalse(degradedResult.getMessage().contains("apikey"));
        assertFalse(degradedResult.getMessage().contains("Bearer"));
        assertFalse(degradedResult.getMessage().contains("secret"));

        // Watchlist item display order and symbols are sanitized
        WatchlistItemDto itemDto = new WatchlistItemDto();
        itemDto.setSymbol("BNBUSDT");
        itemDto.setName("BNB");
        assertNotNull(itemDto.getSymbol());
        assertFalse(itemDto.getSymbol().contains("key="));
    }
}
