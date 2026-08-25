package com.llmgateway.controller;

import com.llmgateway.dto.mobile.MobileAiAnalysisDto;
import com.llmgateway.dto.mobile.MobileNewsBundleResponse;
import com.llmgateway.dto.mobile.MobileNewsDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.dto.news.NewsFeedItemDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ===========================================================================================
 * MOBILE SYNC CONTROLLER - API ĐỒNG BỘ CHUYÊN DỤNG CHO ANDROID (BẠN MẠNH)
 * ===========================================================================================
 *
 * Controller này cung cấp endpoint trả dữ liệu ở FORMAT KHỚP 100% với Room Database
 * của bạn Mạnh (DungQuenTenAnh-1.0.0), bao gồm:
 *   - MobileNewsDto       ←→  Room Entity "News" (newsId, title, url, publishedAt)
 *   - MobileAiAnalysisDto ←→  Room Entity "AI_Analysis" (newsId, summary, sentiment, confidenceScore, reason)
 *
 * Luồng xử lý:
 *   1. Đọc dữ liệu từ Oracle DB (NEWS_AI_CACHE) - cùng nguồn duy nhất với /api/news/feed
 *   2. Chuyển đổi (map) các trường sang đúng format Room DB của Mạnh
 *   3. Trả JSON cho Android → Android insert thẳng vào Room DB → Hiển thị Offline
 *
 * ĐẢM BẢO: KHÔNG GÂY XUNG ĐỘT VỚI CÁC API GỐC CỦA KHÔI (gateway2).
 * ===========================================================================================
 */
@RestController
@RequestMapping("/api/mobile/news")
@Tag(name = "📱 Mobile Sync (Mạnh - Room DB)", description = "API đồng bộ dữ liệu cho Android Room Database - Format khớp 100% với Entity News & AI_Analysis của bạn Mạnh")
public class MobileSyncController {

    private final AiNewsService aiNewsService;

    public MobileSyncController(AiNewsService aiNewsService) {
        this.aiNewsService = aiNewsService;
    }

    /**
     * GET /api/mobile/news/sync?symbol=BTCUSDT&limit=5
     *
     * API chính để Android gọi lấy danh sách tin tức + AI phân tích,
     * trả về format khớp 100% với Room DB của Mạnh.
     *
     * Response JSON mẫu:
     * [
     *   {
     *     "news": { "newsId": "NEWS_1", "title": "...", "url": "...", "publishedAt": 1724500000000 },
     *     "aiAnalysis": { "newsId": "NEWS_1", "summary": "...", "sentiment": "BULLISH", "confidenceScore": 85, "reason": "..." }
     *   }
     * ]
     */
    @GetMapping("/sync")
    @Operation(
            summary = "Đồng bộ Tin tức + AI Analysis cho Room DB Android",
            description = "Lấy danh sách bài báo thật từ Alpha Vantage + Kết quả phân tích Gemini AI, " +
                    "trả về format JSON khớp 100% với Room Entity News & AI_Analysis của bạn Mạnh. " +
                    "Android chỉ cần gọi API này rồi insert thẳng vào Room DB để hiển thị offline."
    )
    public ResponseEntity<List<MobileNewsBundleResponse>> syncNewsForMobile(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "10") int limit) {

        // Bước 1: Lấy dữ liệu từ Oracle DB Cache (cùng nguồn với /api/news/cache)
        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();

        // Bước 2: Map sang format Room DB của Mạnh
        List<MobileNewsBundleResponse> result = cachedNews.stream()
                .filter(item -> symbol == null || symbol.isEmpty() ||
                        (item.getSymbol() != null && item.getSymbol().equalsIgnoreCase(symbol)))
                .limit(limit)
                .map(this::mapToMobileBundle)
                .collect(Collectors.toList());

        // Nếu cache trống, gọi pipeline live feed để nạp dữ liệu mới
        if (result.isEmpty()) {
            List<NewsFeedItemDto> liveFeed = aiNewsService.getLiveAiNewsFeed(symbol, limit);
            // Sau khi gọi live feed, dữ liệu đã được lưu vào Oracle DB Cache
            // Đọc lại cache
            cachedNews = aiNewsService.getAllCachedNews();
            result = cachedNews.stream()
                    .filter(item -> symbol == null || symbol.isEmpty() ||
                            (item.getSymbol() != null && item.getSymbol().equalsIgnoreCase(symbol)))
                    .limit(limit)
                    .map(this::mapToMobileBundle)
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/mobile/news/{newsId}
     *
     * Lấy chi tiết 1 bài báo theo newsId (format "NEWS_xxx").
     */
    @GetMapping("/{newsId}")
    @Operation(
            summary = "Lấy chi tiết 1 bài báo theo newsId",
            description = "Trả về News + AI_Analysis cho 1 bài báo cụ thể theo newsId (format NEWS_xxx)"
    )
    public ResponseEntity<MobileNewsBundleResponse> getNewsById(@PathVariable String newsId) {
        // Parse ID từ format "NEWS_xxx" → Long
        String idStr = newsId.replace("NEWS_", "");
        Long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }

        List<NewsAiCache> allCached = aiNewsService.getAllCachedNews();
        return allCached.stream()
                .filter(item -> item.getId() != null && item.getId().equals(id))
                .findFirst()
                .map(item -> ResponseEntity.ok(mapToMobileBundle(item)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/mobile/news/analysis-only?symbol=BTCUSDT&limit=10
     *
     * Chỉ trả về danh sách AI_Analysis (không kèm News), phù hợp cho
     * trường hợp bạn Mạnh chỉ muốn cập nhật bảng AI_Analysis trong Room DB.
     */
    @GetMapping("/analysis-only")
    @Operation(
            summary = "Chỉ lấy danh sách AI Analysis (không kèm News)",
            description = "Trả về chỉ phần AI phân tích, format khớp 100% với Room Entity AI_Analysis"
    )
    public ResponseEntity<List<MobileAiAnalysisDto>> getAnalysisOnly(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "10") int limit) {

        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();

        List<MobileAiAnalysisDto> result = cachedNews.stream()
                .filter(item -> symbol == null || symbol.isEmpty() ||
                        (item.getSymbol() != null && item.getSymbol().equalsIgnoreCase(symbol)))
                .limit(limit)
                .map(this::mapToMobileAnalysis)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE MAPPER METHODS: Oracle Entity → Mạnh's Room DB Format
    // ═══════════════════════════════════════════════════════════════

    /**
     * Chuyển đổi 1 bản ghi Oracle NEWS_AI_CACHE → Bundle {News + AI_Analysis} cho Room DB.
     */
    private MobileNewsBundleResponse mapToMobileBundle(NewsAiCache entity) {
        MobileNewsDto newsDto = mapToMobileNews(entity);
        MobileAiAnalysisDto analysisDto = mapToMobileAnalysis(entity);
        return new MobileNewsBundleResponse(newsDto, analysisDto);
    }

    /**
     * Map: Oracle NEWS_AI_CACHE → Room Entity "News"
     *
     * Chuyển đổi:
     *   - id (Long)           → newsId (String "NEWS_xxx")
     *   - title (String)      → title (String) ✅ KHỚP
     *   - articleUrl (String)  → url (String) ✅ ĐỔI TÊN
     *   - publishedAt (LocalDateTime) → publishedAt (long, Unix epoch ms) ✅ ĐỔI KIỂU
     */
    private MobileNewsDto mapToMobileNews(NewsAiCache entity) {
        String newsId = "NEWS_" + entity.getId();
        long publishedAtEpoch = entity.getPublishedAt() != null
                ? entity.getPublishedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : System.currentTimeMillis();

        return new MobileNewsDto(
                newsId,
                entity.getTitle(),
                entity.getArticleUrl(),
                publishedAtEpoch
        );
    }

    /**
     * Map: Oracle NEWS_AI_CACHE → Room Entity "AI_Analysis"
     *
     * Chuyển đổi:
     *   - id (Long)              → newsId (String "NEWS_xxx") ✅ FK MAPPING
     *   - summaryPoints (CLOB)   → summary (String) ✅ ĐỔI TÊN
     *   - sentiment (String)     → sentiment (String) ✅ KHỚP
     *   - confidencePct (BigDecimal) → confidenceScore (int) ✅ ĐỔI KIỂU
     *   - reason (CLOB)          → reason (String) ✅ KHỚP
     */
    private MobileAiAnalysisDto mapToMobileAnalysis(NewsAiCache entity) {
        String newsId = "NEWS_" + entity.getId();
        int confidence = entity.getConfidencePct() != null
                ? entity.getConfidencePct().intValue()
                : 0;

        return new MobileAiAnalysisDto(
                newsId,
                entity.getSummaryPoints(),
                entity.getSentiment(),
                confidence,
                entity.getReason()
        );
    }
}
