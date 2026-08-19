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

import java.util.List;

@RestController
@RequestMapping("/api/news")
public class NewsAiController {

    private final AiNewsService aiNewsService;

    public NewsAiController(AiNewsService aiNewsService) {
        this.aiNewsService = aiNewsService;
    }

    /**
     * GET /api/news/feed?symbol=BTCUSDT&limit=5
     * PIPELINE TỰ ĐỘNG 100%:
     * Lấy bài báo THẬT từ Alpha Vantage API -> Đưa qua Gemini AI phân tích -> Lưu Cache CSDL Oracle -> Trả về cho Android.
     */
    @GetMapping("/feed")
    public ResponseEntity<List<NewsFeedItemDto>> getLiveAiNewsFeed(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "5") int limit) {
        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(symbol, limit);
        return ResponseEntity.ok(feed);
    }

    /**
     * POST /api/news/analyze
     * Nhận bài báo -> Kiểm tra CSDL Oracle (hoặc gọi Gemini AI) -> Trả về JSON tóm tắt & gán nhãn Bullish/Bearish
     */
    @PostMapping("/analyze")
    public ResponseEntity<NewsAnalysisResponse> analyzeNews(@Valid @RequestBody NewsAnalysisRequest request) {
        NewsAnalysisResponse response = aiNewsService.analyzeNews(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/news/cache
     * Lấy danh sách toàn bộ các bài báo đã được AI phân tích và lưu trong CSDL Oracle (NEWS_AI_CACHE)
     */
    @GetMapping("/cache")
    public ResponseEntity<List<NewsAiCache>> getCachedNews() {
        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();
        return ResponseEntity.ok(cachedNews);
    }
}
