package com.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.news.AlphaNewsFetchResult;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AiNewsRateLimitBehaviorTest {

    private NewsCacheService newsCacheService;
    private NewsAiCacheRepository newsAiCacheRepository;
    private ObjectMapper objectMapper;
    private AlphaNewsCoordinator alphaNewsCoordinator;
    private AiNewsService aiNewsService;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mock429Response;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        newsCacheService = mock(NewsCacheService.class);
        objectMapper = new ObjectMapper();
        alphaNewsCoordinator = new AlphaNewsCoordinator();
        mockHttpClient = mock(HttpClient.class);
        mock429Response = mock(HttpResponse.class);

        when(mock429Response.statusCode()).thenReturn(429);
        when(mock429Response.body()).thenReturn("{\"error\":{\"code\":429,\"message\":\"RESOURCE_EXHAUSTED\"}}");

        aiNewsService = new AiNewsService(newsCacheService, newsAiCacheRepository, objectMapper, alphaNewsCoordinator);
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "test-key");
        ReflectionTestUtils.setField(aiNewsService, "httpClient", mockHttpClient);
    }

    @Test
    @DisplayName("Khi Gemini phản hồi HTTP 429 ở bài đầu, dừng ngay xử lý các bài sau và phục vụ cache hiện có")
    @SuppressWarnings("unchecked")
    public void testGemini429BreaksLoopAndServesExistingCache() throws Exception {
        // 1. Chuẩn bị 3 bài tin thô từ Alpha Vantage chưa có trong CSDL
        NewsFeedItemDto item1 = new NewsFeedItemDto();
        item1.setTitle("Raw News 1");
        item1.setUrl("https://example.com/news1");
        item1.setSummary("Summary 1");

        NewsFeedItemDto item2 = new NewsFeedItemDto();
        item2.setTitle("Raw News 2");
        item2.setUrl("https://example.com/news2");
        item2.setSummary("Summary 2");

        NewsFeedItemDto item3 = new NewsFeedItemDto();
        item3.setTitle("Raw News 3");
        item3.setUrl("https://example.com/news3");
        item3.setSummary("Summary 3");

        // Cấu hình snapshot Alpha với 3 bài thô
        alphaNewsCoordinator.recordAlphaSnapshot("BTCUSDT", AlphaNewsFetchResult.Status.SUCCESS_WITH_ITEMS, List.of(item1, item2, item3));

        // Chưa có bài nào trong cache khớp với url/title của 3 bài này
        when(newsCacheService.findByArticleUrl(any())).thenReturn(Optional.empty());
        when(newsCacheService.findByTitle(any())).thenReturn(Optional.empty());

        // 2. Chuẩn bị 1 bài đã được dịch tiếng Việt chuẩn trong CSDL Cache trước đó
        NewsAiCache existingCache = new NewsAiCache();
        existingCache.setArticleUrl("https://example.com/cached-article");
        existingCache.setTitle("Original Cached Title");
        existingCache.setOriginalTitle("Original Cached Title");
        existingCache.setDisplayTitleVi("Tiêu đề tiếng Việt chuẩn tài chính");
        existingCache.setDisplaySummaryVi("Nội dung tóm tắt tiếng Việt chuẩn");
        existingCache.setBulletPointsVi("[\"Ý tóm tắt tiếng Việt hợp lệ 1\", \"Ý tóm tắt tiếng Việt hợp lệ 2\"]");
        existingCache.setSummaryPoints("[\"Ý tóm tắt tiếng Việt hợp lệ 1\", \"Ý tóm tắt tiếng Việt hợp lệ 2\"]");
        existingCache.setSymbol("BTC");
        existingCache.setSentiment("BULLISH");
        existingCache.setConfidencePct(BigDecimal.valueOf(85));
        existingCache.setReason("Lý do phân tích tiếng Việt");
        existingCache.setPublishedAt(LocalDateTime.now().minusHours(2));
        existingCache.setAnalyzedAt(LocalDateTime.now().minusHours(2));
        existingCache.setSource("Nguồn tin");

        when(newsCacheService.findBySymbolOrderByPublishedAtDesc(eq("BTCUSDT"), anyInt()))
                .thenReturn(List.of(existingCache));

        // 3. Giả lập khi gọi Gemini: bài đầu tiên trả về HTTP 429
        doReturn(mock429Response).when(mockHttpClient).send(any(), any());

        // 4. Thực thi pipeline
        NewsSyncResult result = aiNewsService.getLiveAiNewsSyncResult("BTCUSDT", 5);

        // 5. Xác thực hành vi nghiêm ngặt:
        // - HttpClient chỉ gọi Gemini ĐÚNG 1 LẦN (dừng ngay, không gọi cho item2 và item3)
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());

        // - Kết quả trả về ok từ cache hiện có
        assertNotNull(result);
        assertEquals("ok", result.getStatus());
        assertFalse(result.getItems().isEmpty());
        assertTrue(result.getItems().get(0).isFromCache());
        assertEquals("Tiêu đề tiếng Việt chuẩn tài chính", result.getItems().get(0).getDisplayTitleVi());

        // - AlphaNewsCoordinator đã ghi nhận lỗi Gemini để kích hoạt cooldown
        assertTrue(alphaNewsCoordinator.isGeminiInCooldown());
    }
}
