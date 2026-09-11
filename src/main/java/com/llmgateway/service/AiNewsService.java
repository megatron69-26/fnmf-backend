package com.llmgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.news.AlphaNewsFetchResult;
import com.llmgateway.dto.news.NewsAnalysisRequest;
import com.llmgateway.dto.news.NewsAnalysisResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.dto.news.NewsSyncResult;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AiNewsService {

    private static final Logger log = LoggerFactory.getLogger(AiNewsService.class);

    private final NewsCacheService newsCacheService;
    private final NewsAiCacheRepository newsAiCacheRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${alphavantage.api.key}")
    private String alphaVantageKey;

    @Value("${alphavantage.api.url:https://www.alphavantage.co/query}")
    private String alphaVantageUrl;

    @Value("${openai.api.key:}")
    private String geminiApiKey;

    @Value("${openai.api.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}")
    private String geminiApiUrl;

    @Value("${openai.default-model:gemini-2.5-flash}")
    private String geminiModel;

    public AiNewsService(NewsCacheService newsCacheService,
                         NewsAiCacheRepository newsAiCacheRepository,
                         ObjectMapper objectMapper) {
        this.newsCacheService = newsCacheService;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;
    public static final int MAX_ALPHA_FETCH = 50;

    public static boolean isValidLimit(int limit) {
        return limit >= 1 && limit <= MAX_LIMIT;
    }

    public static int calculateAlphaFetchCount(int limit) {
        if (!isValidLimit(limit)) {
            throw new IllegalArgumentException("Tham số limit phải nằm trong khoảng từ 1 đến " + MAX_LIMIT);
        }
        return Math.min(Math.multiplyExact(limit, 3), MAX_ALPHA_FETCH);
    }

    // ====================================================================================
    // BẢO VỆ ĐỒ ÁN: TỐI ƯU CHI PHÍ & ĐỘ TRỄ AI BẰNG BỘ NHỚ ĐỆM CSDL POSTGRESQL
    // ------------------------------------------------------------------------------------
    // Câu hỏi:
    //   "Mỗi lần người dùng mở tin tức trên App thì hệ thống có phải gọi Gemini AI liên tục
    //    không? Chi phí token và độ trễ mạng sẽ rất cao, nhóm tối ưu như thế nào?"
    //
    // Câu trả lời của mã nguồn:
    //   1. BỘ NHỚ ĐỆM CSDL: Hệ thống kiểm tra bảng `NEWS_AI_CACHE` trong PostgreSQL
    //      theo `articleUrl` hoặc `title` trước.
    //   2. NẾU ĐÃ CÓ TRONG CSDL: Nạp kết quả phân tích đã được chuẩn hóa tiếng Việt với cờ
    //      `fromCache = true`, không cần gọi lại Gemini AI.
    //   3. NẾU LÀ BÀI MỚI: Gọi Gemini AI dịch và tóm tắt, sau đó lưu vào PostgreSQL
    //      để tái sử dụng cho các yêu cầu tiếp theo.
    public NewsSyncResult getLiveAiNewsSyncResult(String symbol, int limit) {
        if (!isValidLimit(limit)) {
            throw new IllegalArgumentException("Tham số limit phải nằm trong khoảng từ 1 đến " + MAX_LIMIT);
        }
        int fetchCount = calculateAlphaFetchCount(limit);
        AlphaNewsFetchResult alphaResult = fetchRealNewsFromAlphaVantage(symbol, fetchCount);
        List<NewsFeedItemDto> enrichedList = new ArrayList<>();

        if (alphaResult.getStatus() == AlphaNewsFetchResult.Status.SUCCESS_WITH_ITEMS) {
            for (NewsFeedItemDto rawItem : alphaResult.getItems()) {
                final String rawOriginalTitle = (rawItem.getOriginalTitle() != null && !rawItem.getOriginalTitle().isBlank())
                        ? rawItem.getOriginalTitle()
                        : rawItem.getTitle();
                final String url = rawItem.getUrl();
                final String rawSource = rawItem.getSource();
                final String rawSummary = rawItem.getSummary();

                // 1. Kiểm tra CSDL trước qua NewsCacheService (Tối ưu chi phí & độ trễ)
                Optional<NewsAiCache> cachedOpt = Optional.empty();
                if (url != null && !url.isBlank()) {
                    cachedOpt = newsCacheService.findByArticleUrl(url);
                }
                if (cachedOpt.isEmpty() && rawOriginalTitle != null) {
                    cachedOpt = newsCacheService.findByTitle(rawOriginalTitle);
                }

                boolean isCacheLocalized = false;
                if (cachedOpt.isPresent()) {
                    NewsAiCache cached = cachedOpt.get();
                    String cachedOrig = (cached.getOriginalTitle() != null && !cached.getOriginalTitle().isBlank())
                            ? cached.getOriginalTitle()
                            : cached.getTitle();
                    String bulletsToParse = (cached.getBulletPointsVi() != null && !cached.getBulletPointsVi().isBlank())
                            ? cached.getBulletPointsVi()
                            : cached.getSummaryPoints();
                    List<String> rawBullets = parseSummaryPoints(bulletsToParse);
                    List<String> sanitizedBullets = NewsSummaryQualityPolicy.sanitizeBullets(rawBullets, cachedOrig, cached.getOriginalSummary());

                    if (NewsLocalizationQualityPolicy.isFullyLocalized(
                            cached.getDisplayTitleVi(),
                            cachedOrig,
                            sanitizedBullets)) {
                        isCacheLocalized = true;
                    }
                }

                if (isCacheLocalized) {
                    // Đã có trong CSDL Cache với bản dịch tiếng Việt hợp lệ -> Nạp trực tiếp từ CSDL
                    NewsAiCache cached = cachedOpt.get();
                    String publisher = NewsPublisherResolver.resolvePublisher(cached.getSource(), url);
                    if (publisher == null || NewsPublisherResolver.isGeneric(publisher)) {
                        publisher = "";
                    }
                    String cachedOrigTitle = (cached.getOriginalTitle() != null && !cached.getOriginalTitle().isBlank())
                            ? cached.getOriginalTitle()
                            : cached.getTitle();
                    String cachedOrigSummary = (cached.getOriginalSummary() != null && !cached.getOriginalSummary().isBlank())
                            ? cached.getOriginalSummary()
                            : (rawSummary != null && !rawSummary.isBlank() ? rawSummary : cached.getTitle());
                    String displayTitle = cached.getDisplayTitleVi();

                    String bulletsToParse = (cached.getBulletPointsVi() != null && !cached.getBulletPointsVi().isBlank())
                            ? cached.getBulletPointsVi()
                            : cached.getSummaryPoints();
                    List<String> rawBullets = parseSummaryPoints(bulletsToParse);
                    List<String> sanitizedBullets = NewsSummaryQualityPolicy.sanitizeBullets(rawBullets, cachedOrigTitle, cachedOrigSummary);

                    String displaySummary = (cached.getDisplaySummaryVi() != null && !cached.getDisplaySummaryVi().isBlank())
                            ? cached.getDisplaySummaryVi()
                            : ((sanitizedBullets != null && !sanitizedBullets.isEmpty()) ? String.join(" ", sanitizedBullets) : null);

                    rawItem.setOriginalTitle(cachedOrigTitle); // Luôn là tiêu đề gốc nguyên văn
                    rawItem.setOriginalSummary(cachedOrigSummary); // Summary gốc nguyên văn tiếng Anh
                    rawItem.setDisplayTitleVi(displayTitle); // Bản dịch tiếng Việt
                    rawItem.setDisplaySummaryVi(displaySummary); // Đoạn tóm tắt giới thiệu tiếng Việt
                    rawItem.setTitle(displayTitle); // Tương thích ngược với client Android
                    rawItem.setSummary(displaySummary); // Tuyệt đối KHÔNG gán summary tiếng Anh vào summary hiển thị
                    rawItem.setSource(publisher);
                    rawItem.setPublisher(publisher);

                    rawItem.setAiSummary(sanitizedBullets);
                    rawItem.setBulletPointsVi(sanitizedBullets);
                    rawItem.setAiSentiment(cached.getSentiment());
                    rawItem.setAiConfidence(cached.getConfidencePct() != null ? cached.getConfidencePct().intValue() : 85);
                    rawItem.setAiReason(cached.getReason());
                    rawItem.setFromCache(true);
                    if (cached.getAuthor() != null && !cached.getAuthor().isBlank() && (rawItem.getAuthor() == null || rawItem.getAuthor().isBlank())) {
                        rawItem.setAuthor(cached.getAuthor());
                    }
                    if (cached.getBannerImage() != null && !cached.getBannerImage().isBlank() && (rawItem.getBannerImage() == null || rawItem.getBannerImage().isBlank())) {
                        rawItem.setBannerImage(cached.getBannerImage());
                    }
                    enrichedList.add(rawItem);
                } else {
                    // Bài mới hoặc bản ghi cache cũ thiếu displayTitleVi -> Gọi Gemini làm giàu
                    NewsAnalysisRequest aiReq = new NewsAnalysisRequest(rawOriginalTitle, rawSummary, symbol, url);
                    Optional<NewsAnalysisResponse> aiResOpt = analyzeWithGemini(aiReq);

                    if (aiResOpt.isPresent()) {
                        NewsAnalysisResponse aiRes = aiResOpt.get();
                        String displayTitle = aiRes.getDisplayTitleVi();
                        List<String> bullets = aiRes.getSummary();
                        String displaySummary = (aiRes.getDisplaySummaryVi() != null && !aiRes.getDisplaySummaryVi().isBlank())
                                ? aiRes.getDisplaySummaryVi()
                                : ((bullets != null && !bullets.isEmpty()) ? String.join(" ", bullets) : null);
                        String publisher = NewsPublisherResolver.resolvePublisher(rawSource, url);
                        if (publisher == null || NewsPublisherResolver.isGeneric(publisher)) {
                            publisher = "";
                        }

                        boolean fullyLocalized = NewsLocalizationQualityPolicy.isFullyLocalized(
                                displayTitle,
                                rawOriginalTitle,
                                bullets
                        );

                        if (fullyLocalized) {
                            rawItem.setOriginalTitle(rawOriginalTitle); // Bảo toàn nguyên văn tiêu đề Alpha Vantage
                            rawItem.setOriginalSummary(rawSummary); // Bảo toàn summary gốc tiếng Anh
                            rawItem.setDisplayTitleVi(displayTitle); // Bản dịch tiếng Việt
                            rawItem.setDisplaySummaryVi(displaySummary); // Tóm tắt tiếng Việt ngắn
                            rawItem.setTitle(displayTitle); // Tương thích ngược
                            rawItem.setSummary(displaySummary); // UI chỉ nhận tiếng Việt
                            rawItem.setSource(publisher);
                            rawItem.setPublisher(publisher);
                            rawItem.setAiSummary(bullets);
                            rawItem.setBulletPointsVi(bullets);
                            rawItem.setAiSentiment(aiRes.getSentiment());
                            rawItem.setAiConfidence(aiRes.getConfidence());
                            rawItem.setAiReason(aiRes.getReason());
                            rawItem.setFromCache(false);

                            // Lưu/Cập nhật cache độc lập giữ nguyên originalTitle & originalSummary
                            LocalDateTime pubDate = parseAlphaVantageTimestamp(rawItem.getTimePublished());
                            try {
                                newsCacheService.saveCachedArticle(
                                        url,
                                        rawOriginalTitle, // title là rawOriginalTitle
                                        symbol,
                                        objectMapper.writeValueAsString(rawItem.getAiSummary()),
                                        rawItem.getAiSentiment(),
                                        BigDecimal.valueOf(rawItem.getAiConfidence() != null ? rawItem.getAiConfidence() : 85),
                                        rawItem.getAiReason(),
                                        pubDate,
                                        LocalDateTime.now(),
                                        rawItem.getAuthor(),
                                        rawSource,
                                        rawSummary,
                                        rawItem.getBannerImage(),
                                        rawOriginalTitle, // originalTitle độc lập
                                        displayTitle, // displayTitleVi độc lập
                                        displaySummary, // displaySummaryVi độc lập
                                        objectMapper.writeValueAsString(rawItem.getBulletPointsVi())
                                );
                            } catch (Exception e) {
                                log.warn("Không thể lưu cache: {}", e.getMessage());
                            }
                            enrichedList.add(rawItem);
                        } else {
                            log.warn("Gemini xử lý tiêu đề/bullet chưa đạt chuẩn tiếng Việt, bỏ qua bài: {}", rawOriginalTitle);
                        }
                    } else {
                        log.warn("Gemini API xử lý thất bại hoặc không khả dụng, bỏ qua bài: {}", rawOriginalTitle);
                    }
                }

                if (enrichedList.size() >= limit) {
                    break;
                }
            }
        }

        // Nếu danh sách bài từ Alpha Vantage rỗng hoặc không có bài nào dịch được, nạp các bài đã có bản dịch hợp lệ từ Cache
        if (enrichedList.isEmpty()) {
            log.info("Không có bài mới từ Alpha Vantage hoặc chưa dịch được, tự động tìm tin đã có tiếng Việt trong Cache CSDL...");
            List<NewsAiCache> cachedList = (symbol != null && !symbol.isBlank())
                    ? newsCacheService.findBySymbolOrderByPublishedAtDesc(symbol.toUpperCase(), limit * 3)
                    : newsCacheService.findTopByOrderByPublishedAtDesc(limit * 3);
            if (cachedList.isEmpty()) {
                cachedList = newsCacheService.findTopByOrderByPublishedAtDesc(limit * 3);
            }
            if (cachedList.isEmpty()) {
                cachedList = newsCacheService.findAll(limit * 3);
            }
            for (NewsAiCache c : cachedList) {
                String cOrigTitle = (c.getOriginalTitle() != null && !c.getOriginalTitle().isBlank())
                        ? c.getOriginalTitle()
                        : c.getTitle();
                String cOrigSummary = (c.getOriginalSummary() != null && !c.getOriginalSummary().isBlank())
                        ? c.getOriginalSummary()
                        : c.getTitle();
                String publisher = NewsPublisherResolver.resolvePublisher(c.getSource(), c.getArticleUrl());
                if (publisher == null || NewsPublisherResolver.isGeneric(publisher)) {
                    publisher = "";
                }
                String bulletsToParse = (c.getBulletPointsVi() != null && !c.getBulletPointsVi().isBlank())
                        ? c.getBulletPointsVi()
                        : c.getSummaryPoints();
                List<String> rawBullets = parseSummaryPoints(bulletsToParse);
                List<String> sanitizedBullets = NewsSummaryQualityPolicy.sanitizeBullets(rawBullets, cOrigTitle, cOrigSummary);

                String displaySummary = (c.getDisplaySummaryVi() != null && !c.getDisplaySummaryVi().isBlank())
                        ? c.getDisplaySummaryVi()
                        : ((sanitizedBullets != null && !sanitizedBullets.isEmpty()) ? String.join(" ", sanitizedBullets) : null);

                if (NewsLocalizationQualityPolicy.isFullyLocalized(
                        c.getDisplayTitleVi(),
                        cOrigTitle,
                        sanitizedBullets)) {
                    NewsFeedItemDto dto = new NewsFeedItemDto();
                    dto.setOriginalTitle(cOrigTitle);
                    dto.setOriginalSummary(cOrigSummary);
                    dto.setDisplayTitleVi(c.getDisplayTitleVi());
                    dto.setDisplaySummaryVi(displaySummary);
                    dto.setTitle(c.getDisplayTitleVi());
                    dto.setSummary(displaySummary); // Tuyệt đối KHÔNG gán cOrigSummary tiếng Anh
                    dto.setUrl(c.getArticleUrl());
                    dto.setTimePublished(c.getPublishedAt() != null ? c.getPublishedAt().toString() : "");
                    dto.setSource(publisher);
                    dto.setPublisher(publisher);
                    dto.setBannerImage(c.getBannerImage());
                    dto.setAuthor(c.getAuthor());
                    dto.setCategory("Market");
                    dto.setAiSummary(sanitizedBullets);
                    dto.setBulletPointsVi(sanitizedBullets);
                    dto.setAiSentiment(c.getSentiment());
                    dto.setAiConfidence(c.getConfidencePct() != null ? c.getConfidencePct().intValue() : 85);
                    dto.setAiReason(c.getReason());
                    dto.setFromCache(true);
                    enrichedList.add(dto);
                    if (enrichedList.size() >= limit) break;
                }
            }
        }

        // Quy tắc phân định trạng thái chính xác:
        // 1. Có cache tiếng Việt hợp lệ -> status=ok, kể cả provider đang lỗi
        if (!enrichedList.isEmpty()) {
            return NewsSyncResult.ok(enrichedList);
        }

        // 2. Alpha SUCCESS_EMPTY + không có cache hợp lệ -> status=empty
        if (alphaResult.getStatus() == AlphaNewsFetchResult.Status.SUCCESS_EMPTY) {
            return NewsSyncResult.empty("Chưa có bản tin mới");
        }

        // 3. Alpha UNAVAILABLE hoặc Alpha có bài nhưng Gemini thất bại toàn bộ -> status=degraded
        return NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
    }

    public List<NewsFeedItemDto> getLiveAiNewsFeed(String symbol, int limit) {
        if (!isValidLimit(limit)) {
            throw new IllegalArgumentException("Tham số limit phải nằm trong khoảng từ 1 đến " + MAX_LIMIT);
        }
        return getLiveAiNewsSyncResult(symbol, limit).getItems();
    }

    /**
     * Phân tích bài báo bất kỳ (dùng cho trường hợp truyền tay bài báo)
     */
    public NewsAnalysisResponse analyzeNews(NewsAnalysisRequest request) {
        final String rawOriginalTitle = request.getTitle().trim();
        final String url = request.getArticleUrl() != null ? request.getArticleUrl().trim() : null;

        Optional<NewsAiCache> cachedOpt = Optional.empty();
        if (url != null && !url.isBlank()) {
            cachedOpt = newsCacheService.findByArticleUrl(url);
        }
        if (cachedOpt.isEmpty()) {
            cachedOpt = newsCacheService.findByTitle(rawOriginalTitle);
        }

        if (cachedOpt.isPresent()) {
            NewsAiCache cached = cachedOpt.get();
            String cachedOrig = (cached.getOriginalTitle() != null && !cached.getOriginalTitle().isBlank())
                    ? cached.getOriginalTitle()
                    : cached.getTitle();
            String displayTitle = (cached.getDisplayTitleVi() != null && !cached.getDisplayTitleVi().isBlank())
                    ? cached.getDisplayTitleVi()
                    : null;
            String bulletsToParse = (cached.getBulletPointsVi() != null && !cached.getBulletPointsVi().isBlank())
                    ? cached.getBulletPointsVi()
                    : cached.getSummaryPoints();
            List<String> rawBullets = parseSummaryPoints(bulletsToParse);
            List<String> sanitizedBullets = NewsSummaryQualityPolicy.sanitizeBullets(rawBullets, cachedOrig, request.getContent());
            String displaySummary = (cached.getDisplaySummaryVi() != null && !cached.getDisplaySummaryVi().isBlank())
                    ? cached.getDisplaySummaryVi()
                    : ((sanitizedBullets != null && !sanitizedBullets.isEmpty()) ? String.join(" ", sanitizedBullets) : null);

            NewsAnalysisResponse cachedResp = new NewsAnalysisResponse(
                    cachedOrig,
                    displayTitle,
                    cached.getSymbol(),
                    sanitizedBullets,
                    sanitizedBullets,
                    cached.getSentiment(),
                    cached.getConfidencePct() != null ? cached.getConfidencePct().intValue() : 85,
                    cached.getReason(),
                    true
            );
            cachedResp.setOriginalSummary(cached.getOriginalSummary() != null ? cached.getOriginalSummary() : request.getContent());
            cachedResp.setDisplaySummaryVi(displaySummary);
            return cachedResp;
        }

        Optional<NewsAnalysisResponse> aiOpt = analyzeWithGemini(request);
        if (aiOpt.isEmpty()) {
            return new NewsAnalysisResponse(
                    rawOriginalTitle,
                    null,
                    request.getSymbol(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    "NEUTRAL",
                    50,
                    "Không thể phân tích bằng mô hình AI vào lúc này.",
                    false
            );
        }
        NewsAnalysisResponse aiResult = aiOpt.get();
        String displayTitle = (aiResult.getDisplayTitleVi() != null && !aiResult.getDisplayTitleVi().isBlank())
                ? aiResult.getDisplayTitleVi()
                : null;
        String displaySummary = (aiResult.getDisplaySummaryVi() != null && !aiResult.getDisplaySummaryVi().isBlank())
                ? aiResult.getDisplaySummaryVi()
                : ((aiResult.getSummary() != null && !aiResult.getSummary().isEmpty()) ? String.join(" ", aiResult.getSummary()) : null);

        // Lưu vào CSDL Cache qua NewsCacheService độc lập
        try {
            newsCacheService.saveCachedArticle(
                    url != null && !url.isBlank() ? url : "custom_" + System.currentTimeMillis(),
                    rawOriginalTitle,
                    request.getSymbol() != null ? request.getSymbol().toUpperCase() : "GENERAL",
                    objectMapper.writeValueAsString(aiResult.getSummary()),
                    aiResult.getSentiment(),
                    BigDecimal.valueOf(aiResult.getConfidence()),
                    aiResult.getReason(),
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    null,
                    null,
                    request.getContent(),
                    null,
                    rawOriginalTitle,
                    displayTitle,
                    displaySummary,
                    objectMapper.writeValueAsString(aiResult.getSummary())
            );
        } catch (Exception e) {
            log.error("Không thể lưu cache: {}", e.getMessage());
        }

        aiResult.setFromCache(false);
        aiResult.setOriginalTitle(rawOriginalTitle);
        aiResult.setOriginalSummary(request.getContent());
        aiResult.setDisplayTitleVi(displayTitle);
        aiResult.setDisplaySummaryVi(displaySummary);
        return aiResult;
    }

    public List<NewsAiCache> getAllCachedNews() {
        return newsCacheService.findAll();
    }

    // =========================================================================
    // PRIVATE METHODS
    // =========================================================================

    private AlphaNewsFetchResult fetchRealNewsFromAlphaVantage(String symbol, int limit) {
        if (alphaVantageKey == null || alphaVantageKey.isBlank() || alphaVantageKey.startsWith("${")) {
            log.info("Alpha Vantage API Key chưa được cung cấp hoặc rỗng, tự động lấy tin bài từ CSDL Cache...");
            return AlphaNewsFetchResult.unavailable("Alpha Vantage API Key chưa được cấu hình");
        }
        try {
            String baseUrl = (alphaVantageUrl != null && !alphaVantageUrl.isBlank()) ? alphaVantageUrl : "https://www.alphavantage.co/query";
            String tickerParam = "";
            if (symbol != null && !symbol.isBlank()) {
                String clean = symbol.toUpperCase();
                if (clean.contains("BTC")) tickerParam = "&tickers=CRYPTO:BTC";
                else if (clean.contains("ETH")) tickerParam = "&tickers=CRYPTO:ETH";
                else if (clean.contains("XAU")) tickerParam = "&tickers=FOREX:USD";
                else if (clean.contains("OIL")) tickerParam = "&topics=energy_transportation";
            }

            String url = String.format("%s?function=NEWS_SENTIMENT%s&topics=financial_markets,technology&limit=%d&apikey=%s",
                    baseUrl, tickerParam, limit > 0 ? limit : 5, alphaVantageKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Alpha Vantage API trả về mã lỗi HTTP {}", response.statusCode());
                return AlphaNewsFetchResult.unavailable("Alpha Vantage trả về mã HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("Note") || root.has("Information")) {
                String msg = root.has("Note") ? root.path("Note").asText() : root.path("Information").asText();
                log.warn("Alpha Vantage API Rate Limit / Thông báo hệ thống: {}", msg);
                return AlphaNewsFetchResult.unavailable("Alpha Vantage rate limit / thông báo: " + msg);
            }
            if (root.has("Error Message")) {
                String errorMsg = root.path("Error Message").asText();
                log.error("Alpha Vantage API Error Message: {}", errorMsg);
                return AlphaNewsFetchResult.unavailable("Alpha Vantage error: " + errorMsg);
            }

            JsonNode feed = root.path("feed");
            if (!feed.isArray()) {
                return AlphaNewsFetchResult.unavailable("Dữ liệu feed không hợp lệ từ Alpha Vantage");
            }

            List<NewsFeedItemDto> list = new ArrayList<>();
            for (JsonNode node : feed) {
                String title = node.path("title").asText();
                String articleUrl = node.path("url").asText();
                String timePublished = node.path("time_published").asText();
                String summary = node.path("summary").asText();
                String bannerImage = node.path("banner_image").asText(null);
                String rawSource = node.path("source").asText(null);
                String rawPublisher = NewsPublisherResolver.resolvePublisher(rawSource, articleUrl);
                String publisher = (rawPublisher != null && !NewsPublisherResolver.isGeneric(rawPublisher)) ? rawPublisher : "";
                String category = node.path("category_within_source").asText("Market");

                List<String> topics = new ArrayList<>();
                for (JsonNode t : node.path("topics")) {
                    topics.add(t.path("topic").asText());
                }

                NewsFeedItemDto dto = new NewsFeedItemDto(title, articleUrl, timePublished, summary, bannerImage, publisher, category, topics, null, null, null, null, false);
                dto.setPublisher(publisher);
                dto.setSource(publisher);
                dto.setOriginalTitle(title);
                dto.setTitle(title);
                dto.setDisplayTitleVi(null); // Không dùng regex thay từ, để Gemini dịch
                String authorVal = "";
                JsonNode authors = node.path("authors");
                if (authors.isArray() && authors.size() > 0) {
                    String authorCandidate = authors.get(0).asText();
                    if (authorCandidate != null && !authorCandidate.isBlank() && !authorCandidate.equalsIgnoreCase(publisher) && !NewsPublisherResolver.isGeneric(authorCandidate)) {
                        authorVal = authorCandidate.trim();
                    }
                }
                dto.setAuthor(authorVal);
                list.add(dto);
                if (list.size() >= limit) {
                    break;
                }
            }
            if (list.isEmpty()) {
                return AlphaNewsFetchResult.empty();
            }
            return AlphaNewsFetchResult.success(list);
        } catch (Exception e) {
            log.warn("Lỗi khi tải bài báo thật từ Alpha Vantage: {}", e.getMessage());
            return AlphaNewsFetchResult.unavailable("Lỗi kết nối Alpha Vantage: " + e.getMessage());
        }
    }

    /**
     * ====================================================================================
     * 🧠 [LUỒNG CHÍNH] GỌI GOOGLE GEMINI API VỚI SYSTEM PROMPT CHUYÊN GIA TÀI CHÍNH
     * ====================================================================================
     */
    public Optional<NewsAnalysisResponse> analyzeWithGemini(NewsAnalysisRequest request) {
        if (geminiApiKey == null || geminiApiKey.isBlank() || geminiApiKey.startsWith("${")) {
            log.warn("Gemini API Key chưa được cấu hình hoặc rỗng.");
            return Optional.empty();
        }

        String targetSymbol = request.getSymbol() != null ? request.getSymbol() : "Thị trường tài chính";
        String systemPrompt = """
            Bạn là Chuyên gia Phân tích Tài chính và Biên tập viên Tin tức Kinh tế cấp cao của hệ thống FNMF.
            Dữ liệu đầu vào là một bài báo tài chính THỰC TẾ từ nguồn tin quốc tế.
            Nhiệm vụ của bạn:
            1. Dịch tiêu đề bài báo sang tiếng Việt chuẩn ngữ cảnh tài chính tự nhiên ("displayTitleVi"). Tuyệt đối KHÔNG dịch tên riêng công ty, tên người, mã cổ phiếu/tài sản (INTU, NVDA, BTC, ETH...), thương hiệu nhà xuất bản (MarketBeat, Yahoo Finance, CNBC...), con số và đơn vị đo lường.
            2. Tóm tắt nội dung bài báo thành từ 2 đến 4 gạch đầu dòng ("summary") bằng tiếng Việt cô đọng, súc tích, phản ánh đúng dữ kiện thực tế từ bài báo (số liệu, mốc thời gian, quyết định kinh doanh).
            3. Tuyệt đối KHÔNG sử dụng các câu mở đầu khuôn mẫu thừa như "Trọng tâm tin tức:", "Tác động thị trường:", "Khuyến nghị FNMF:", "Bài viết này nói về...", "Dưới đây là tóm tắt...".
            4. Tuyệt đối KHÔNG đưa ra khuyến nghị mua bán tài chính cá nhân.
            5. Đánh giá tác động đến giá tài sản (%s) theo 3 nhãn:
               - BULLISH (Cơ hội / Tín hiệu tăng giá)
               - BEARISH (Rủi ro / Tín hiệu giảm giá)
               - NEUTRAL (Trung lập / Đi ngang / Ít tác động)
            6. Đưa ra chỉ số độ tin cậy (từ 0 đến 100).
            7. Viết 1-2 câu ngắn gọn giải thích lý do dựa trên bối cảnh kinh tế.
            
            QUY TẮC BẮT BUỘC:
            - Trả về DUY NHẤT một chuỗi JSON hợp lệ.
            - KHÔNG thêm bất kỳ văn bản giải thích nào ngoài JSON.
            - KHÔNG bọc JSON trong dấu ```json ... ```.
            
            Định dạng JSON yêu cầu:
            {
              "displayTitleVi": "Tiêu đề tiếng Việt chuẩn tài chính",
              "summary": ["Ý thực chất 1", "Ý thực chất 2", "Ý thực chất 3"],
              "sentiment": "BULLISH",
              "confidence": 90,
              "reason": "Giải thích ngắn gọn lý do."
            }
            """.formatted(targetSymbol);

        String userPrompt = "Tiêu đề: " + request.getTitle() + "\nNội dung tóm tắt: " + request.getContent();

        try {
            Map<String, Object> body = Map.of(
                    "model", geminiModel,
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            String jsonPayload = objectMapper.writeValueAsString(body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(geminiApiUrl))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + geminiApiKey)
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String rawText = root.path("choices").get(0).path("message").path("content").asText().trim();

                if (rawText.startsWith("```json")) rawText = rawText.substring(7);
                if (rawText.startsWith("```")) rawText = rawText.substring(3);
                if (rawText.endsWith("```")) rawText = rawText.substring(0, rawText.length() - 3);
                rawText = rawText.trim();

                JsonNode parsedJson = objectMapper.readTree(rawText);
                String displayTitleVi = parsedJson.path("displayTitleVi").asText(null);
                if (displayTitleVi != null && !NewsLocalizationQualityPolicy.isValidDisplayTitleVi(displayTitleVi, request.getTitle())) {
                    displayTitleVi = null;
                }

                List<String> summary = new ArrayList<>();
                if (parsedJson.has("summary") && parsedJson.get("summary").isArray()) {
                    for (JsonNode item : parsedJson.get("summary")) {
                        summary.add(item.asText());
                    }
                }
                summary = NewsSummaryQualityPolicy.sanitizeBullets(summary, request.getTitle(), request.getContent());
                String displaySummaryVi = parsedJson.path("displaySummaryVi").asText(null);
                if (displaySummaryVi == null || !NewsLocalizationQualityPolicy.isValidDisplaySummaryVi(displaySummaryVi, request.getContent(), displayTitleVi)) {
                    if (summary != null && !summary.isEmpty()) {
                        displaySummaryVi = String.join(" ", summary);
                    }
                }
                String sentiment = parsedJson.path("sentiment").asText("NEUTRAL").toUpperCase();
                int confidence = parsedJson.path("confidence").asInt(85);
                String reason = parsedJson.path("reason").asText("Phân tích từ dữ liệu tin tức kinh tế.");

                NewsAnalysisResponse resp = new NewsAnalysisResponse(request.getTitle(), displayTitleVi, request.getSymbol(), summary, summary, sentiment, confidence, reason, false);
                resp.setOriginalSummary(request.getContent());
                resp.setDisplaySummaryVi(displaySummaryVi);
                return Optional.of(resp);
            } else {
                log.warn("Gemini API trả về mã lỗi HTTP {}", response.statusCode());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("Lỗi khi gọi Gemini API: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public LocalDateTime parseAlphaVantageTimestamp(String timePublished) {
        if (timePublished == null || timePublished.isBlank()) {
            return null;
        }
        try {
            DateTimeFormatter avFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
            return LocalDateTime.parse(timePublished, avFormatter);
        } catch (Exception ignored) {}
        try {
            return LocalDateTime.parse(timePublished, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception ignored) {}
        try {
            return LocalDateTime.parse(timePublished, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> parseSummaryPoints(String summaryPointsJson) {
        if (summaryPointsJson == null || summaryPointsJson.isBlank()) {
            return List.of("Không có bản tóm tắt chi tiết.");
        }
        try {
            return objectMapper.readValue(summaryPointsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of(summaryPointsJson);
        }
    }

    /**
     * Chẩn đoán trạng thái hệ thống tin tức & kết nối an toàn (Section E).
     * Tuyệt đối không trả về API key, độ dài key, hay biến nội bộ nhạy cảm.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diag = new java.util.LinkedHashMap<>();

        boolean alphaVantageConfigured = alphaVantageKey != null && !alphaVantageKey.isBlank() && !alphaVantageKey.startsWith("${");
        boolean geminiConfigured = geminiApiKey != null && !geminiApiKey.isBlank() && !geminiApiKey.startsWith("${");
        String effectiveModel = (geminiModel != null && !geminiModel.isBlank() && !geminiModel.startsWith("${")) ? geminiModel : "gemini-2.5-flash";

        diag.put("status", (alphaVantageConfigured && geminiConfigured) ? "HEALTHY" : "DEGRADED");
        diag.put("alphaVantageConfigured", alphaVantageConfigured);
        diag.put("geminiConfigured", geminiConfigured);
        diag.put("geminiModel", effectiveModel);
        diag.put("databaseCacheCount", newsCacheService.count());

        log.info("NEWS PIPELINE DIAGNOSTICS | status={} | alphaConfigured={} | geminiConfigured={} | model='{}' | cacheCount={}",
                diag.get("status"), alphaVantageConfigured, geminiConfigured, effectiveModel, diag.get("databaseCacheCount"));

        return diag;
    }
}
