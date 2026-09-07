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
    public ResponseEntity<List<NewsFeedItemDto>> getLiveAiNewsFeed(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit) {
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
        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(symbol, limit);
        List<Map<String, Object>> data = new ArrayList<>();
        int idCounter = 1;
        for (NewsFeedItemDto item : feed) {
            Map<String, Object> map = new HashMap<>();
            String idVal = (item.getUrl() != null && !item.getUrl().isBlank())
                    ? item.getUrl()
                    : String.valueOf(idCounter++);
            map.put("id", idVal);
            map.put("title", item.getTitle() != null ? item.getTitle() : "");
            String summaryVal = (item.getSummary() != null && !item.getSummary().isBlank())
                    ? item.getSummary()
                    : (item.getAiSummary() != null && !item.getAiSummary().isEmpty() ? String.join(" ", item.getAiSummary()) : "");
            map.put("summary", summaryVal);
            map.put("source", item.getSource() != null ? item.getSource() : "Unknown");
            map.put("publishedAt", item.getTimePublished() != null ? item.getTimePublished() : "");
            map.put("imageUrl", item.getBannerImage() != null ? item.getBannerImage() : "");
            String sentiment = item.getAiSentiment() != null ? item.getAiSentiment().toLowerCase() : "neutral";
            map.put("sentiment", sentiment);
            map.put("confidence", item.getAiConfidence() != null ? item.getAiConfidence() : 0);
            map.put("bulletPoints", item.getAiSummary() != null ? item.getAiSummary() : new ArrayList<>());
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
