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
     * PIPELINE Tá»° Äá»˜NG 100%:
     * Láº¥y bÃ i bÃ¡o THáº¬T tá»« Alpha Vantage API -> ÄÆ°a qua Gemini AI phÃ¢n tÃ­ch -> LÆ°u Cache CSDL Oracle -> Tráº£ vá» cho Android.
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
     * Nháº­n bÃ i bÃ¡o -> Kiá»ƒm tra CSDL Oracle (hoáº·c gá»i Gemini AI) -> Tráº£ vá» JSON tÃ³m táº¯t & gÃ¡n nhÃ£n Bullish/Bearish
     */
    @PostMapping("/analyze")
    public ResponseEntity<NewsAnalysisResponse> analyzeNews(@Valid @RequestBody NewsAnalysisRequest request) {
        NewsAnalysisResponse response = aiNewsService.analyzeNews(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/news/cache
     * Láº¥y danh sÃ¡ch toÃ n bá»™ cÃ¡c bÃ i bÃ¡o Ä‘Ã£ Ä‘Æ°á»£c AI phÃ¢n tÃ­ch vÃ  lÆ°u trong CSDL Oracle (NEWS_AI_CACHE)
     */
    @GetMapping("/cache")
    public ResponseEntity<List<NewsAiCache>> getCachedNews() {
        List<NewsAiCache> cachedNews = aiNewsService.getAllCachedNews();
        return ResponseEntity.ok(cachedNews);
    }
}

