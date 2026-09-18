package com.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.news.NewsAnalysisRequest;
import com.llmgateway.dto.news.NewsAnalysisResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.filter.ProductionSecurityFilter;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.service.provider.GeminiShardRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class NewsShardRoutingAndPipelineBehavioralTest {

    private GeminiShardRouter shardRouter;
    private NewsCacheService newsCacheService;
    private NewsAiCacheRepository newsAiCacheRepository;
    private ObjectMapper objectMapper;
    private AlphaNewsCoordinator coordinator;
    private AiNewsService aiNewsService;

    private static final String KEY_SHARD_1 = "test-gemini-key-shard-1-abc";
    private static final String KEY_SHARD_2 = "test-gemini-key-shard-2-def";
    private static final String KEY_SHARD_3 = "test-gemini-key-shard-3-ghi";
    private static final String KEY_ALPHA = "test-alpha-key-xyz";

    @BeforeEach
    public void setUp() {
        shardRouter = new GeminiShardRouter();
        shardRouter.setShardKeys(KEY_SHARD_1, KEY_SHARD_2, KEY_SHARD_3);
        shardRouter.setMarketShard("GEMINI_SHARD_2");
        shardRouter.setNewsShard("GEMINI_SHARD_3");

        newsCacheService = mock(NewsCacheService.class);
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        objectMapper = new ObjectMapper();
        coordinator = new AlphaNewsCoordinator();

        aiNewsService = new AiNewsService(newsCacheService, newsAiCacheRepository, objectMapper, coordinator, shardRouter);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", KEY_ALPHA);
    }

    @Test
    @DisplayName("Test 1: NewsRouterShardSelectionTest - GEMINI_NEWS_SHARD=GEMINI_SHARD_3 định tuyến đúng Shard 3")
    public void test1_NewsRouterShardSelectionTest() {
        shardRouter.setNewsShard("GEMINI_SHARD_3");
        GeminiShardRouter.GeminiShardInfo info = shardRouter.resolveNewsShard();

        assertNotNull(info);
        assertEquals(GeminiShardRouter.SHARD_3, info.shardName());
        assertEquals(KEY_SHARD_3, info.apiKey());
        assertEquals("GEMINI_SHARD_3", shardRouter.resolveNewsShardName());
    }

    @Test
    @DisplayName("Test 2: NewsRouterDefaultShardTest - Không đặt biến môi trường -> Mặc định GEMINI_SHARD_3")
    public void test2_NewsRouterDefaultShardTest() {
        shardRouter.setNewsShard(null);
        GeminiShardRouter.GeminiShardInfo info = shardRouter.resolveNewsShard();

        assertNotNull(info);
        assertEquals(GeminiShardRouter.SHARD_3, info.shardName());
        assertEquals(KEY_SHARD_3, info.apiKey());

        shardRouter.setNewsShard("   ");
        info = shardRouter.resolveNewsShard();
        assertEquals(GeminiShardRouter.SHARD_3, info.shardName());
    }

    @Test
    @DisplayName("Test 3: NewsRouterExplicitShard1Test - Đặt GEMINI_SHARD_1 -> Định tuyến đúng Shard 1 key")
    public void test3_NewsRouterExplicitShard1Test() {
        shardRouter.setNewsShard("GEMINI_SHARD_1");
        GeminiShardRouter.GeminiShardInfo info = shardRouter.resolveNewsShard();

        assertNotNull(info);
        assertEquals(GeminiShardRouter.SHARD_1, info.shardName());
        assertEquals(KEY_SHARD_1, info.apiKey());
        assertEquals("GEMINI_SHARD_1", shardRouter.resolveNewsShardName());
    }

    @Test
    @DisplayName("Test 4: NewsRouterInvalidShardFailClosedTest - Đặt giá trị không hợp lệ -> Ném exception, fail-closed")
    public void test4_NewsRouterInvalidShardFailClosedTest() {
        shardRouter.setNewsShard("GEMINI_SHARD_99");

        ForecastUnavailableException ex = assertThrows(ForecastUnavailableException.class, () -> {
            shardRouter.resolveNewsShard();
        });
        assertTrue(ex.getMessage().contains("GEMINI_NEWS_SHARD không hợp lệ"), "Phải báo rõ cấu hình shard không hợp lệ");

        assertThrows(ForecastUnavailableException.class, () -> {
            shardRouter.resolveNewsShardName();
        });
    }

    @Test
    @DisplayName("Test 5: NewsServiceUsesRouterShardTest - AiNewsService dùng key từ router, không dùng openai.api.key")
    public void test5_NewsServiceUsesRouterShardTest() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockHttpResponse = mock(HttpResponse.class);

        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("""
            {
              "choices": [
                {
                  "message": {
                    "content": "{\\"displayTitleVi\\": \\"Thị trường tăng mạnh\\", \\"summary\\": [\\"Điểm tin 1\\", \\"Điểm tin 2\\"], \\"sentiment\\": \\"BULLISH\\", \\"confidence\\": 90, \\"reason\\": \\"Tăng giá\\"}"
                  }
                }
              ]
            }
            """);

        when(mockHttpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest req = invocation.getArgument(0);
            Optional<String> authHeader = req.headers().firstValue("Authorization");
            assertTrue(authHeader.isPresent());
            assertEquals("Bearer " + KEY_SHARD_3, authHeader.get(), "Header Authorization phải chứa key của GEMINI_SHARD_3");
            return mockHttpResponse;
        });

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttpClient);

        NewsAnalysisRequest req = new NewsAnalysisRequest("Market rallies", "Stock markets surged today", "BTC", "http://example.com/1");
        Optional<NewsAnalysisResponse> resp = aiNewsService.analyzeWithGemini(req);

        assertTrue(resp.isPresent());
        assertEquals("GEMINI_SHARD_3", aiNewsService.getSelectedNewsShardName());
    }

    @Test
    @DisplayName("Test 6: NewsServiceGeminiCooldownOn403Test - Khi Gemini trả 403, kích hoạt cooldown, không gọi bài tiếp theo, giữ cache CSDL")
    public void test6_NewsServiceGeminiCooldownOn403Test() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);

        when(mockHttpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest req = invocation.getArgument(0);
            String uri = req.uri().toString();
            if (uri.contains("alphavantage")) {
                HttpResponse<String> alphaRes = mock(HttpResponse.class);
                when(alphaRes.statusCode()).thenReturn(200);
                when(alphaRes.body()).thenReturn("""
                    {
                      "feed": [
                        {
                          "title": "Raw News 1",
                          "url": "http://example.com/raw1",
                          "time_published": "20260918T010000",
                          "summary": "Raw summary 1",
                          "source": "Reuters"
                        }
                      ]
                    }
                    """);
                return alphaRes;
            }
            HttpResponse<String> geminiRes = mock(HttpResponse.class);
            when(geminiRes.statusCode()).thenReturn(403);
            when(geminiRes.body()).thenReturn("{\"error\": \"Forbidden\"}");
            return geminiRes;
        });

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttpClient);

        NewsAiCache cachedDb = new NewsAiCache();
        cachedDb.setId(10L);
        cachedDb.setArticleUrl("http://old.com/1");
        cachedDb.setSymbol("BTCUSDT");
        cachedDb.setTitle("Original English Title");
        cachedDb.setOriginalTitle("Original English Title");
        cachedDb.setDisplayTitleVi("Thị trường Bitcoin khởi sắc");
        cachedDb.setOriginalSummary("Original summary in English text");
        cachedDb.setDisplaySummaryVi("Tóm tắt thị trường tích cực");
        cachedDb.setSummaryPoints("[\"Điểm tin phân tích thứ nhất\", \"Điểm tin phân tích thứ hai\"]");
        cachedDb.setBulletPointsVi("[\"Điểm tin phân tích thứ nhất\", \"Điểm tin phân tích thứ hai\"]");
        cachedDb.setPublishedAt(LocalDateTime.now().minusHours(2));
        cachedDb.setAnalyzedAt(LocalDateTime.now().minusMinutes(10));
        cachedDb.setSentiment("BULLISH");

        when(newsCacheService.findBySymbolOrderByPublishedAtDesc(anyString(), anyInt())).thenReturn(List.of(cachedDb));
        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(cachedDb));

        NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5, true);

        assertTrue(coordinator.isGeminiInCooldown(), "Gemini phải rơi vào cooldown sau khi gặp HTTP 403");

        assertTrue(result.isStale(), "Kết quả trả về phải là stale khi Gemini lỗi và dùng cache");
        assertEquals("ok", result.getStatus());
        assertEquals("Thị trường Bitcoin khởi sắc", result.getItems().get(0).getTitle());
    }

    @Test
    @DisplayName("Test 7: NewsServiceGeminiCooldownOn429Test - Khi Gemini trả 429, ném rate limit exception và kích hoạt cooldown")
    public void test7_NewsServiceGeminiCooldownOn429Test() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockHttpResponse = mock(HttpResponse.class);

        when(mockHttpResponse.statusCode()).thenReturn(429);
        when(mockHttpResponse.body()).thenReturn("{\"error\": \"Rate limit exceeded\"}");
        doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());

        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttpClient);

        NewsAnalysisRequest req = new NewsAnalysisRequest("Market", "Summary", "BTC", "http://example.com/1");
        assertThrows(AiNewsService.GeminiRateLimitException.class, () -> {
            aiNewsService.analyzeWithGemini(req);
        });

        assertTrue(coordinator.isGeminiInCooldown(), "Gemini phải rơi vào cooldown sau khi gặp HTTP 429");
    }

    @Test
    @DisplayName("Test 8: NewsServiceAlphaAndGeminiDecoupledTest - Alpha thành công nhưng Gemini lỗi, Alpha snapshot được giữ, không fetch lại Alpha")
    public void test8_NewsServiceAlphaAndGeminiDecoupledTest() {
        NewsFeedItemDto rawItem = new NewsFeedItemDto("Alpha News", "http://example.com/alpha", "20260918T010000", "Summary", null, "Bloomberg", "Market", List.of(), null, null, null, null, false);
        coordinator.recordAlphaSuccess(List.of(rawItem), "GLOBAL");

        assertTrue(coordinator.isAlphaFresh(), "Snapshot Alpha phải fresh");
        assertNotNull(coordinator.getCachedSnapshot());
        assertEquals(1, coordinator.getCachedSnapshot().getRawItems().size());

        coordinator.recordGeminiFailure();
        assertTrue(coordinator.isGeminiInCooldown());
        assertTrue(coordinator.isAlphaFresh(), "Lỗi Gemini không được làm mất snapshot Alpha");
    }

    @Test
    @DisplayName("Test 9: NewsServicePreservesCacheOnGeminiFailureTest - CSDL cache không bị xóa khi Gemini lỗi, response stale=true")
    public void test9_NewsServicePreservesCacheOnGeminiFailureTest() {
        NewsAiCache cached = new NewsAiCache();
        cached.setId(1L);
        cached.setArticleUrl("http://keep.com/1");
        cached.setSymbol("BTCUSDT");
        cached.setTitle("Original Market News");
        cached.setOriginalTitle("Original Market News");
        cached.setDisplayTitleVi("Bản tin phân tích tài chính");
        cached.setOriginalSummary("Market updates overview");
        cached.setDisplaySummaryVi("Tóm tắt bản tin cập nhật thị trường");
        cached.setSummaryPoints("[\"Điểm tin cập nhật số một\", \"Điểm tin cập nhật số hai\"]");
        cached.setBulletPointsVi("[\"Điểm tin cập nhật số một\", \"Điểm tin cập nhật số hai\"]");
        cached.setPublishedAt(LocalDateTime.now().minusHours(25));
        cached.setAnalyzedAt(LocalDateTime.now().minusMinutes(120)); // > 90 phút -> cache không fresh
        cached.setSentiment("NEUTRAL");

        when(newsCacheService.findBySymbolOrderByPublishedAtDesc(anyString(), anyInt())).thenReturn(List.of(cached));
        when(newsCacheService.findTopByOrderByPublishedAtDesc(anyInt())).thenReturn(List.of(cached));

        // Snapshot Alpha có bài nhưng Gemini rơi vào cooldown
        NewsFeedItemDto rawItem = new NewsFeedItemDto("Raw Market", "http://new.com/1", "20260918T010000", "Summary", null, "Reuters", "Market", List.of(), null, null, null, null, false);
        coordinator.recordAlphaSuccess(List.of(rawItem), "GLOBAL");
        coordinator.recordGeminiFailure();

        NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5, false);

        verify(newsAiCacheRepository, never()).deleteAll();
        assertTrue(result.isStale(), "Khi cache cũ và Gemini cooldown, kết quả phải stale");
        assertEquals("ok", result.getStatus());
        assertEquals(1, result.getItems().size());
        assertEquals("Bản tin phân tích tài chính", result.getItems().get(0).getTitle());
    }

    @Test
    @DisplayName("Test 10: NewsDiagnosticsSafePayloadTest - Diagnostics trả metadata đầy đủ nhưng KHÔNG chứa API key hay key length")
    public void test10_NewsDiagnosticsSafePayloadTest() {
        when(newsCacheService.count()).thenReturn(42L);

        Map<String, Object> diag = aiNewsService.getDiagnostics();

        assertNotNull(diag);
        assertEquals("HEALTHY", diag.get("status"));
        assertEquals(true, diag.get("alphaConfigured"));
        assertEquals(true, diag.get("selectedNewsShardConfigured"));
        assertEquals("GEMINI_SHARD_3", diag.get("selectedNewsShard"));
        assertEquals(42L, diag.get("cachedArticleCount"));
        assertEquals(false, diag.get("alphaCooldownActive"));
        assertEquals(false, diag.get("geminiCooldownActive"));
        assertEquals("NONE", diag.get("alphaLastFailureCode"));

        for (Map.Entry<String, Object> entry : diag.entrySet()) {
            String key = entry.getKey();
            String valStr = String.valueOf(entry.getValue());

            assertFalse(valStr.contains(KEY_SHARD_1), "Key nhạy cảm không được xuất hiện trong " + key);
            assertFalse(valStr.contains(KEY_SHARD_2), "Key nhạy cảm không được xuất hiện trong " + key);
            assertFalse(valStr.contains(KEY_SHARD_3), "Key nhạy cảm không được xuất hiện trong " + key);
            assertFalse(valStr.contains(KEY_ALPHA), "Key nhạy cảm không được xuất hiện trong " + key);
            assertFalse(key.toLowerCase().contains("length"), "Không được để lộ trường key length");
        }
    }

    @Test
    @DisplayName("Test 11: NewsDiagnosticsAccessibleTest - ProductionSecurityFilter không chặn /api/news/diagnostics")
    public void test11_NewsDiagnosticsAccessibleTest() {
        ProductionSecurityFilter filter = new ProductionSecurityFilter();

        assertFalse(filter.isBlockedPath("/api/news/diagnostics"), "Endpoint diagnostics an toàn không được bị chặn");
        assertFalse(filter.isBlockedPath("/api/news/diagnostics/"), "Endpoint diagnostics an toàn không được bị chặn");

        assertTrue(filter.isBlockedPath("/h2-console"));
        assertTrue(filter.isBlockedPath("/swagger-ui"));
        assertTrue(filter.isBlockedPath("/api/admin/db/query"));
    }

    @Test
    @DisplayName("Test 12: ForecastMarketShardUnchangedTest - Forecast MARKET vẫn dùng GEMINI_SHARD_2 độc lập")
    public void test12_ForecastMarketShardUnchangedTest() {
        shardRouter.setMarketShard("GEMINI_SHARD_2");
        shardRouter.setNewsShard("GEMINI_SHARD_3");

        GeminiShardRouter.GeminiShardInfo marketInfo = shardRouter.resolveShard("MARKET");
        assertEquals("GEMINI_SHARD_2", marketInfo.shardName());
        assertEquals(KEY_SHARD_2, marketInfo.apiKey());

        GeminiShardRouter.GeminiShardInfo newsInfo = shardRouter.resolveNewsShard();
        assertEquals("GEMINI_SHARD_3", newsInfo.shardName());
        assertEquals(KEY_SHARD_3, newsInfo.apiKey());

        assertEquals("GEMINI_SHARD_2", shardRouter.resolveShardName("MARKET"));
        assertEquals("GEMINI_SHARD_3", shardRouter.resolveNewsShardName());
    }
}
