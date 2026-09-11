package com.llmgateway.controller;

import com.llmgateway.dto.news.NewsAnalysisRequest;
import com.llmgateway.dto.news.NewsAnalysisResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.service.AiNewsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
public class NewsAiController {

    private final AiNewsService aiNewsService;

    public NewsAiController(AiNewsService aiNewsService) {
        this.aiNewsService = aiNewsService;
    }

    /**
     * GET /api/news/feed?symbol=BTCUSDT&limit=5
     */
    @GetMapping("/feed")
    public ResponseEntity<?> getLiveAiNewsFeed(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit) {
        if (!AiNewsService.isValidLimit(limit)) {
            Map<String, Object> errResp = new HashMap<>();
            errResp.put("status", "error");
            errResp.put("message", "Tham số limit phải nằm trong khoảng từ 1 đến " + AiNewsService.MAX_LIMIT);
            errResp.put("data", java.util.Collections.emptyList());
            return ResponseEntity.badRequest().body(errResp);
        }
        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(symbol, limit);
        if (limit > 0 && feed.size() > limit) {
            feed = feed.subList(0, limit);
        }
        return ResponseEntity.ok(feed);
    }

    /**
     * GET /api/news/sync?limit=5
     * Endpoint cho Android APK 1.1.4
     */
    @GetMapping("/sync")
    public ResponseEntity<Map<String, Object>> getSyncNewsFeed(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit) {
        if (!AiNewsService.isValidLimit(limit)) {
            Map<String, Object> errResp = new HashMap<>();
            errResp.put("status", "error");
            errResp.put("message", "Tham số limit phải nằm trong khoảng từ 1 đến " + AiNewsService.MAX_LIMIT);
            errResp.put("data", java.util.Collections.emptyList());
            return ResponseEntity.badRequest().body(errResp);
        }

        com.llmgateway.dto.news.NewsSyncResult syncResult = aiNewsService.getLiveAiNewsSyncResult(symbol, limit);
        if (!"ok".equals(syncResult.getStatus())) {
            Map<String, Object> statusResp = new HashMap<>();
            statusResp.put("status", syncResult.getStatus());
            statusResp.put("message", syncResult.getMessage());
            statusResp.put("data", java.util.Collections.emptyList());
            return ResponseEntity.ok(statusResp);
        }

        List<NewsFeedItemDto> feed = syncResult.getItems();
        if (feed == null || feed.isEmpty()) {
            Map<String, Object> emptyResp = new HashMap<>();
            emptyResp.put("status", "empty");
            emptyResp.put("message", "Chưa có bản tin mới");
            emptyResp.put("data", java.util.Collections.emptyList());
            return ResponseEntity.ok(emptyResp);
        }

        List<Map<String, Object>> data = new ArrayList<>();
        int idCounter = 1;
        for (NewsFeedItemDto item : feed) {
            Map<String, Object> map = new HashMap<>();
            String idVal = (item.getUrl() != null && !item.getUrl().isBlank())
                    ? item.getUrl()
                    : String.valueOf(idCounter++);
            map.put("id", idVal);
            String origTitle = (item.getOriginalTitle() != null && !item.getOriginalTitle().isBlank())
                    ? item.getOriginalTitle().trim()
                    : "";
            String displayTitle = (item.getDisplayTitleVi() != null && !item.getDisplayTitleVi().isBlank())
                    ? item.getDisplayTitleVi().trim()
                    : (item.getTitle() != null ? item.getTitle().trim() : "");
            map.put("originalTitle", origTitle);
            map.put("displayTitleVi", displayTitle);
            map.put("title", displayTitle);

            String origSummary = (item.getOriginalSummary() != null && !item.getOriginalSummary().isBlank())
                    ? item.getOriginalSummary().trim()
                    : "";
            map.put("originalSummary", origSummary);

            List<String> bulletsVi = (item.getBulletPointsVi() != null && !item.getBulletPointsVi().isEmpty())
                    ? item.getBulletPointsVi()
                    : (item.getAiSummary() != null ? item.getAiSummary() : new ArrayList<>());

            String displaySummary = (item.getDisplaySummaryVi() != null && !item.getDisplaySummaryVi().isBlank())
                    ? item.getDisplaySummaryVi().trim()
                    : ((bulletsVi != null && !bulletsVi.isEmpty()) ? String.join(" ", bulletsVi).trim() : "");

            map.put("displaySummaryVi", displaySummary);
            map.put("summary", displaySummary);

            String publisher = (item.getPublisher() != null && !item.getPublisher().isBlank())
                    ? item.getPublisher().trim()
                    : com.llmgateway.service.NewsPublisherResolver.resolvePublisher(item.getSource(), item.getUrl());
            if (com.llmgateway.service.NewsPublisherResolver.isGeneric(publisher)) {
                publisher = "";
            }
            map.put("source", publisher != null ? publisher : "");
            map.put("publisher", publisher != null ? publisher : "");
            map.put("publishedAt", item.getTimePublished() != null ? item.getTimePublished() : "");
            map.put("imageUrl", item.getBannerImage() != null ? item.getBannerImage() : "");
            String sentiment = item.getAiSentiment() != null ? item.getAiSentiment().toLowerCase() : "neutral";
            map.put("sentiment", sentiment);
            map.put("confidence", item.getAiConfidence() != null ? item.getAiConfidence() : 0);
            map.put("bulletPoints", bulletsVi);
            map.put("bulletPointsVi", bulletsVi);
            map.put("author", item.getAuthor() != null ? item.getAuthor() : "");
            map.put("link", item.getUrl() != null ? item.getUrl() : "");
            data.add(map);
            if (limit > 0 && data.size() >= limit) {
                break;
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/news/diagnostics
     * Xác minh an toàn hệ thống tin tức & pipeline (Section E)
     */
    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        return ResponseEntity.ok(aiNewsService.getDiagnostics());
    }

    /**
     * POST /api/news/analyze
     */
    @PostMapping("/analyze")
    public ResponseEntity<NewsAnalysisResponse> analyzeNews(@Valid @RequestBody NewsAnalysisRequest request) {
        NewsAnalysisResponse response = aiNewsService.analyzeNews(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/news/cache
     */
    @GetMapping("/cache")
    public ResponseEntity<List<NewsAiCache>> getCachedNews() {
        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();
        return ResponseEntity.ok(cachedNews);
    }
}
