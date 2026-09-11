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
import org.springframework.beans.factory.annotation.Autowired;
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
    private String alphaVantageUrl = "https://www.alphavantage.co/query";

    @Value("${openai.api.key:}")
    private String geminiApiKey;

    @Value("${openai.api.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}")
    private String geminiApiUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

    @Value("${openai.default-model:gemini-3.6-flash}")
    private String geminiModel = "gemini-3.6-flash";

    private final AlphaNewsCoordinator alphaNewsCoordinator;

    @Autowired
    public AiNewsService(NewsCacheService newsCacheService,
                         NewsAiCacheRepository newsAiCacheRepository,
                         ObjectMapper objectMapper,
                         AlphaNewsCoordinator alphaNewsCoordinator) {
        this.newsCacheService = newsCacheService;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.objectMapper = objectMapper;
        this.alphaNewsCoordinator = alphaNewsCoordinator != null ? alphaNewsCoordinator : new AlphaNewsCoordinator();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public AiNewsService(NewsCacheService newsCacheService,
                         NewsAiCacheRepository newsAiCacheRepository,
                         ObjectMapper objectMapper) {
        this(newsCacheService, newsAiCacheRepository, objectMapper, new AlphaNewsCoordinator());
    }

    public AlphaNewsCoordinator getAlphaNewsCoordinator() {
        return alphaNewsCoordinator;
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

        final String requestedScope = AlphaNewsCoordinator.normalizeScope(symbol);

        // 1. Kiểm tra cache PostgreSQL tiếng Việt hợp lệ hiện có
        List<NewsFeedItemDto> cachedItems = getValidLocalizedCacheItems(symbol, limit);

        // 2. Nếu cache tiếng Việt có bài và còn mới (trong vòng 90 phút theo analyzedAt) -> Trả ngay
        if (isCacheFresh(cachedItems)) {
            log.info("Sử dụng cache tin tức tiếng Việt PostgreSQL còn mới (trong vòng {} phút), không cần gọi Alpha Vantage",
                    alphaNewsCoordinator.getRefreshIntervalMinutes());
            return NewsSyncResult.ok(cachedItems);
        }

        // 3. Nếu đang trong thời gian Cooldown lỗi của Alpha Vantage
        if (alphaNewsCoordinator.isInCooldown()) {
            if (!cachedItems.isEmpty()) {
                log.warn("Alpha Vantage đang trong thời gian cooldown ({}), trả cache tiếng Việt hiện có",
                        alphaNewsCoordinator.getLastFailureCode());
                return NewsSyncResult.ok(cachedItems);
            }
            return NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
        }

        // 4. Cơ chế Single-Flight bao phủ TOÀN BỘ PIPELINE:
        // Alpha fetch / snapshot reuse -> Gemini processing -> validate -> persist PostgreSQL -> tạo NewsSyncResult.
        // Không thả lock trước khi hoàn tất toàn bộ pipeline để tránh trùng lặp Gemini & DB.
        boolean lockAcquired = alphaNewsCoordinator.tryAcquireRefresh();
        if (!lockAcquired) {
            if (!cachedItems.isEmpty()) {
                log.info("Request đồng thời đang làm mới dữ liệu, trả cache tiếng Việt hiện có cho luồng này");
                return NewsSyncResult.ok(cachedItems);
            }
            return NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
        }

        try {
            // Re-check cache sau khi có lock (trong trường hợp luồng trước vừa hoàn tất ghi DB)
            cachedItems = getValidLocalizedCacheItems(symbol, limit);
            if (isCacheFresh(cachedItems)) {
                return NewsSyncResult.ok(cachedItems);
            }

            // Kiểm tra snapshot Alpha trong RAM
            AlphaNewsCoordinator.CachedAlphaSnapshot snapshot = alphaNewsCoordinator.getCachedSnapshot();
            List<NewsFeedItemDto> rawItemsToProcess = null;
            boolean needAlphaFetch = true;

            if (snapshot != null && snapshot.isFresh(alphaNewsCoordinator.getClock(), alphaNewsCoordinator.getRefreshIntervalMinutes())
                    && snapshot.matchesScope(requestedScope)) {

                if (snapshot.getStatus() == AlphaNewsFetchResult.Status.SUCCESS_EMPTY) {
                    log.info("Snapshot Alpha xác nhận thị trường không có tin tức cho scope {}, replay empty", snapshot.getScope());
                    if (!cachedItems.isEmpty()) {
                        return NewsSyncResult.ok(cachedItems);
                    }
                    return NewsSyncResult.empty("Chưa có bản tin mới");
                }

                if (snapshot.getStatus() == AlphaNewsFetchResult.Status.SUCCESS_WITH_ITEMS) {
                    if (alphaNewsCoordinator.isGeminiInCooldown()) {
                        log.warn("Gemini đang trong thời gian cooldown sau lỗi trước đó, không gọi lại Gemini");
                        if (!cachedItems.isEmpty()) {
                            return NewsSyncResult.ok(cachedItems);
                        }
                        return NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
                    }
                    log.info("Tái sử dụng raw feed Alpha Vantage ({}) từ bộ nhớ để xử lý qua Gemini, không gọi lại provider", snapshot.getScope());
                    rawItemsToProcess = snapshot.getRawItems();
                    needAlphaFetch = false;
                }
            }

            if (needAlphaFetch) {
                int fetchCount = calculateAlphaFetchCount(limit);
                AlphaNewsFetchResult alphaResult = fetchRealNewsFromAlphaVantage(symbol, fetchCount);

                if (alphaResult.getStatus() == AlphaNewsFetchResult.Status.UNAVAILABLE) {
                    alphaNewsCoordinator.recordFailure(alphaResult.getMessage());
                    if (!cachedItems.isEmpty()) {
                        return NewsSyncResult.ok(cachedItems);
                    }
                    return NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
                }

                if (alphaResult.getStatus() == AlphaNewsFetchResult.Status.SUCCESS_EMPTY) {
                    alphaNewsCoordinator.recordAlphaSnapshot(requestedScope, AlphaNewsFetchResult.Status.SUCCESS_EMPTY, Collections.emptyList());
                    if (!cachedItems.isEmpty()) {
                        return NewsSyncResult.ok(cachedItems);
                    }
                    return NewsSyncResult.empty("Chưa có bản tin mới");
                }

                // SUCCESS_WITH_ITEMS
                alphaNewsCoordinator.recordAlphaSnapshot(requestedScope, AlphaNewsFetchResult.Status.SUCCESS_WITH_ITEMS, alphaResult.getItems());
                rawItemsToProcess = alphaResult.getItems();
            }

            List<NewsFeedItemDto> enrichedList = new ArrayList<>();
            if (rawItemsToProcess != null && !rawItemsToProcess.isEmpty()) {
                for (NewsFeedItemDto rawItem : rawItemsToProcess) {
                    final String rawOriginalTitle = (rawItem.getOriginalTitle() != null && !rawItem.getOriginalTitle().isBlank())
                            ? rawItem.getOriginalTitle()
                            : rawItem.getTitle();
                    final String url = rawItem.getUrl();
                    final String rawSource = rawItem.getSource();
                    final String rawSummary = rawItem.getSummary();

                    // Kiểm tra CSDL trước qua NewsCacheService (Tối ưu chi phí & độ trễ)
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

                        rawItem.setOriginalTitle(cachedOrigTitle);
                        rawItem.setOriginalSummary(cachedOrigSummary);
                        rawItem.setDisplayTitleVi(displayTitle);
                        rawItem.setDisplaySummaryVi(displaySummary);
                        rawItem.setTitle(displayTitle);
                        rawItem.setSummary(displaySummary);
                        rawItem.setSource(publisher);
                        rawItem.setPublisher(publisher);

                        rawItem.setAiSummary(sanitizedBullets);
                        rawItem.setBulletPointsVi(sanitizedBullets);
                        rawItem.setAiSentiment(cached.getSentiment());
                        rawItem.setAiConfidence(cached.getConfidencePct() != null ? cached.getConfidencePct().intValue() : 85);
                        rawItem.setAiReason(cached.getReason());
                        rawItem.setFromCache(true);
                        rawItem.setAnalyzedAt(cached.getAnalyzedAt() != null ? cached.getAnalyzedAt().toString() : null);
                        if (cached.getAuthor() != null && !cached.getAuthor().isBlank() && (rawItem.getAuthor() == null || rawItem.getAuthor().isBlank())) {
                            rawItem.setAuthor(cached.getAuthor());
                        }
                        if (cached.getBannerImage() != null && !cached.getBannerImage().isBlank() && (rawItem.getBannerImage() == null || rawItem.getBannerImage().isBlank())) {
                            rawItem.setBannerImage(cached.getBannerImage());
                        }
                        enrichedList.add(rawItem);
                    } else {
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
                                rawItem.setOriginalTitle(rawOriginalTitle);
                                rawItem.setOriginalSummary(rawSummary);
                                rawItem.setDisplayTitleVi(displayTitle);
                                rawItem.setDisplaySummaryVi(displaySummary);
                                rawItem.setTitle(displayTitle);
                                rawItem.setSummary(displaySummary);
                                rawItem.setSource(publisher);
                                rawItem.setPublisher(publisher);
                                rawItem.setAiSummary(bullets);
                                rawItem.setBulletPointsVi(bullets);
                                rawItem.setAiSentiment(aiRes.getSentiment());
                                rawItem.setAiConfidence(aiRes.getConfidence());
                                rawItem.setAiReason(aiRes.getReason());
                                rawItem.setFromCache(false);
                                LocalDateTime nowTime = LocalDateTime.now(alphaNewsCoordinator.getClock());
                                rawItem.setAnalyzedAt(nowTime.toString());

                                LocalDateTime pubDate = parseAlphaVantageTimestamp(rawItem.getTimePublished());
                                try {
                                    newsCacheService.saveCachedArticle(
                                            url,
                                            rawOriginalTitle,
                                            symbol,
                                            objectMapper.writeValueAsString(rawItem.getAiSummary()),
                                            rawItem.getAiSentiment(),
                                            BigDecimal.valueOf(rawItem.getAiConfidence() != null ? rawItem.getAiConfidence() : 85),
                                            rawItem.getAiReason(),
                                            pubDate,
                                            nowTime,
                                            rawItem.getAuthor(),
                                            rawSource,
                                            rawSummary,
                                            rawItem.getBannerImage(),
                                            rawOriginalTitle,
                                            displayTitle,
                                            displaySummary,
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

            if (!enrichedList.isEmpty()) {
                alphaNewsCoordinator.recordPipelineSuccess();
                alphaNewsCoordinator.recordGeminiSuccess();
                return NewsSyncResult.ok(enrichedList);
            }

            // Nếu có raw items mà không có bài nào enriched (Gemini lỗi toàn bộ)
            if (rawItemsToProcess != null && !rawItemsToProcess.isEmpty()) {
                alphaNewsCoordinator.recordGeminiFailure();
            }

            // Fallback sang cache CSDL nếu có
            List<NewsFeedItemDto> fallbackCache = getValidLocalizedCacheItems(symbol, limit);
            if (!fallbackCache.isEmpty()) {
                return NewsSyncResult.ok(fallbackCache);
            }

            return NewsSyncResult.degraded("Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng");
        } finally {
            alphaNewsCoordinator.releaseRefresh();
        }
    }

    public List<NewsFeedItemDto> getValidLocalizedCacheItems(String symbol, int limit) {
        List<NewsFeedItemDto> validList = new ArrayList<>();
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
                dto.setSummary(displaySummary);
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
                dto.setAnalyzedAt(c.getAnalyzedAt() != null ? c.getAnalyzedAt().toString() : null);
                validList.add(dto);
                if (validList.size() >= limit) break;
            }
        }
        return validList;
    }

    public boolean isCacheFresh(List<NewsFeedItemDto> cacheList) {
        // Invariant 1: Tuyệt đối không coi cache rỗng là fresh
        if (cacheList == null || cacheList.isEmpty()) {
            return false;
        }

        // Invariant 2: Xác định độ mới DUY NHẤT theo analyzedAt (thời điểm backend phân tích/lưu cache).
        // Tuyệt đối không fallback sang publishedAt/timePublished vì tin cũ vẫn có thể vừa phân tích,
        // và cache thiếu analyzedAt phải coi là STALE.
        String firstAnalyzedAt = cacheList.get(0).getAnalyzedAt();
        if (firstAnalyzedAt == null || firstAnalyzedAt.isBlank()) {
            return false;
        }
        try {
            LocalDateTime analyzedTime = LocalDateTime.parse(firstAnalyzedAt);
            return alphaNewsCoordinator.isCacheFresh(analyzedTime);
        } catch (Exception ignored) {
            return false;
        }
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
            return AlphaNewsFetchResult.unavailable("ALPHA_KEY_NOT_CONFIGURED");
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
                log.warn("ALPHA_HTTP_ERROR");
                return AlphaNewsFetchResult.httpError();
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(response.body());
            } catch (Exception pe) {
                log.warn("ALPHA_INVALID_RESPONSE");
                return AlphaNewsFetchResult.invalidResponse();
            }

            if (root.has("Note") || root.has("Information")) {
                log.warn("ALPHA_RATE_LIMITED");
                return AlphaNewsFetchResult.rateLimited();
            }
            if (root.has("Error Message")) {
                log.warn("ALPHA_INVALID_RESPONSE");
                return AlphaNewsFetchResult.invalidResponse();
            }

            JsonNode feed = root.path("feed");
            if (!feed.isArray()) {
                log.warn("ALPHA_INVALID_RESPONSE");
                return AlphaNewsFetchResult.invalidResponse();
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
            log.warn("ALPHA_NETWORK_ERROR");
            return AlphaNewsFetchResult.networkError();
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
        String effectiveModel = (geminiModel != null && !geminiModel.isBlank() && !geminiModel.startsWith("${")) ? geminiModel : "gemini-3.6-flash";

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
