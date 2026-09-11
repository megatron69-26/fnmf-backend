package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.DataInitializer;
import com.llmgateway.controller.MobileSyncController;
import com.llmgateway.dto.mobile.MobileNewsBundleResponse;
import com.llmgateway.dto.news.NewsFeedItemDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.AuthService;
import com.llmgateway.service.NewsCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class NewsServiceAndMigrationTest {

    private NewsAiCacheRepository newsAiCacheRepository;
    private NewsCacheService newsCacheService;
    private AiNewsService aiNewsService;
    private MobileSyncController mobileSyncController;
    private DataInitializer dataInitializer;
    private UserRepository userRepository;
    private AuthService authService;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private ResourceLoader resourceLoader;
    private org.springframework.core.env.Environment env;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    public void setUp() {
        newsAiCacheRepository = mock(NewsAiCacheRepository.class);
        newsCacheService = new NewsCacheService(newsAiCacheRepository);
        objectMapper = new ObjectMapper();

        userRepository = mock(UserRepository.class);
        authService = mock(AuthService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        resourceLoader = mock(ResourceLoader.class);
        env = mock(org.springframework.core.env.Environment.class);
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);

        aiNewsService = new AiNewsService(newsCacheService, newsAiCacheRepository, objectMapper);
        ReflectionTestUtils.setField(aiNewsService, "alphaVantageKey", ""); // Rỗng để luôn dùng CSDL Cache
        ReflectionTestUtils.setField(aiNewsService, "geminiApiKey", "");

        mobileSyncController = new MobileSyncController(aiNewsService, newsCacheService);

        dataInitializer = new DataInitializer(
                userRepository,
                authService,
                newsCacheService,
                jdbcTemplate,
                objectMapper,
                resourceLoader,
                env,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("1. Seed giữ nguyên publishedAt và analyzedAt từ file seed, không tạo ngày giả")
    public void testSeedPreservesPublishedAt() {
        when(newsAiCacheRepository.count()).thenReturn(0L);

        String sampleJson = "[{\n" +
                "  \"id\": 99,\n" +
                "  \"articleUrl\": \"https://example.com/real-news-1\",\n" +
                "  \"title\": \"AMD AI Breakthrough in 2026\",\n" +
                "  \"symbol\": \"AMD\",\n" +
                "  \"summaryPoints\": \"[\\\"Point 1\\\"]\",\n" +
                "  \"sentiment\": \"BULLISH\",\n" +
                "  \"confidencePct\": 90,\n" +
                "  \"reason\": \"Growth in AI\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08.771531\",\n" +
                "  \"analyzedAt\": \"2026-09-08T02:37:08.771531\"\n" +
                "}]";

        Resource mockResource = new ByteArrayResource(sampleJson.getBytes());
        when(resourceLoader.getResource("classpath:news_cache_seed.json")).thenReturn(mockResource);
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());

        dataInitializer.seedNewsCacheIfNeeded();

        ArgumentCaptor<Iterable<NewsAiCache>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(newsAiCacheRepository, atLeastOnce()).saveAll(captor.capture());

        List<NewsAiCache> savedList = new ArrayList<>();
        captor.getValue().forEach(savedList::add);

        assertEquals(1, savedList.size());
        NewsAiCache saved = savedList.get(0);
        assertNull(saved.getId(), "Không dùng ID cũ, để CSDL tự sinh Identity");
        assertEquals(LocalDateTime.parse("2026-09-08T02:37:08.771531"), saved.getPublishedAt(),
                "Ngày phát hành phải giữ nguyên ngày thật từ file seed, không được là LocalDateTime.now()");
        assertEquals(LocalDateTime.parse("2026-09-08T02:37:08.771531"), saved.getAnalyzedAt());
    }

    @Test
    @DisplayName("2. Seed chống trùng lặp theo articleUrl trong cùng file seed")
    public void testSeedPreventsDuplicateArticleUrl() {
        when(newsAiCacheRepository.count()).thenReturn(0L);

        String duplicateJson = "[{\n" +
                "  \"articleUrl\": \"https://example.com/same-url\",\n" +
                "  \"title\": \"First Instance\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08\"\n" +
                "},{\n" +
                "  \"articleUrl\": \"https://example.com/same-url\",\n" +
                "  \"title\": \"Duplicate Instance\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08\"\n" +
                "}]";

        Resource mockResource = new ByteArrayResource(duplicateJson.getBytes());
        when(resourceLoader.getResource("classpath:news_cache_seed.json")).thenReturn(mockResource);
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());

        dataInitializer.seedNewsCacheIfNeeded();

        ArgumentCaptor<Iterable<NewsAiCache>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(newsAiCacheRepository, atLeastOnce()).saveAll(captor.capture());

        List<NewsAiCache> savedList = new ArrayList<>();
        captor.getValue().forEach(savedList::add);

        assertEquals(1, savedList.size(), "Chỉ lưu 1 bài duy nhất khi articleUrl bị trùng");
        assertEquals("First Instance", savedList.get(0).getTitle());
    }

    @Test
    @DisplayName("3. Seed không ghi đè bài báo đang tồn tại trong CSDL")
    public void testSeedDoesNotOverwriteExistingData() {
        when(newsAiCacheRepository.count()).thenReturn(2L); // Thiếu dữ liệu (< 5)
        String existingUrl = "https://example.com/already-exists";

        NewsAiCache existingEntity = new NewsAiCache();
        existingEntity.setId(10L);
        existingEntity.setArticleUrl(existingUrl);
        existingEntity.setTitle("Existing Unmodified Title");

        when(newsAiCacheRepository.findByArticleUrl(existingUrl)).thenReturn(Optional.of(existingEntity));
        when(newsAiCacheRepository.existsByArticleUrl(existingUrl)).thenReturn(true);

        String jsonWithExisting = "[{\n" +
                "  \"articleUrl\": \"https://example.com/already-exists\",\n" +
                "  \"title\": \"New Seed Title Attempting Overwrite\",\n" +
                "  \"publishedAt\": \"2026-09-08T02:37:08\"\n" +
                "}]";

        Resource mockResource = new ByteArrayResource(jsonWithExisting.getBytes());
        when(resourceLoader.getResource("classpath:news_cache_seed.json")).thenReturn(mockResource);

        dataInitializer.seedNewsCacheIfNeeded();

        // Không có bản ghi mới nào được saveAll
        verify(newsAiCacheRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("4. Tuyệt đối KHÔNG TRUNCATE bảng nếu bảng có dữ liệu khi migration OID PostgreSQL")
    public void testMigrationDoesNotTruncateTableWithData() throws Exception {
        // Giả lập PostgreSQL connection metadata
        Connection mockConn = mock(Connection.class);
        DatabaseMetaData mockMeta = mock(DatabaseMetaData.class);
        when(mockMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConn.getMetaData()).thenReturn(mockMeta);

        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(mockConn);
        });

        // Bảng tồn tại
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM information_schema.tables"), eq(Integer.class)))
                .thenReturn(1);

        // Có cột OID
        when(jdbcTemplate.query(contains("oid"), any(RowMapper.class)))
                .thenReturn(List.of("summary_points", "reason"));

        // Bảng CÓ 20 bản ghi
        when(jdbcTemplate.queryForObject(eq("SELECT count(*) FROM news_ai_cache"), eq(Long.class)))
                .thenReturn(20L);

        // LOB không thể đọc an toàn (lo_get ném lỗi)
        when(jdbcTemplate.queryForObject(contains("lo_get"), eq(Integer.class)))
                .thenThrow(new RuntimeException("Unable to access lob stream"));

        boolean result = dataInitializer.migratePostgresLobColumnsIfNeeded();

        assertFalse(result, "Migration phải dừng lại và trả false nếu không bảo toàn được LOB");
        // Kiểm tra tuyệt đối không gọi TRUNCATE
        verify(jdbcTemplate, never()).execute(contains("TRUNCATE"));
    }

    @Test
    @DisplayName("5. Luồng live news lưu cache thật qua NewsCacheService")
    public void testLiveNewsFlowCanSaveRealCache() {
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(100L);
            return entity;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://example.com/test-live",
                "Apple Releases New M4 Chip",
                "AAPL",
                "[\"Trọng tâm: Apple chip mới\"]",
                "BULLISH",
                BigDecimal.valueOf(92),
                "Strong financial growth",
                LocalDateTime.of(2026, 9, 8, 10, 0),
                LocalDateTime.of(2026, 9, 8, 10, 5)
        );

        assertTrue(saved.isPresent());
        assertEquals(100L, saved.get().getId());
        assertEquals("BULLISH", saved.get().getSentiment());
        assertEquals("AAPL", saved.get().getSymbol());
        verify(newsAiCacheRepository, times(1)).save(any(NewsAiCache.class));
    }

    @Test
    @DisplayName("6. Fallback đọc cache trả tối đa limit đã chỉ định")
    public void testFallbackCacheRespectsLimit() {
        List<NewsAiCache> cachedArticles = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            NewsAiCache item = new NewsAiCache();
            item.setId((long) i);
            item.setTitle("Bitcoin Market Update " + i);
            item.setOriginalTitle("Bitcoin Market Update " + i);
            item.setDisplayTitleVi("Cập nhật thị trường Bitcoin phiên " + i);
            item.setOriginalSummary("Bitcoin trading volume remained steady with key support holding.");
            item.setDisplaySummaryVi("Khối lượng giao dịch Bitcoin duy trì ổn định với vùng hỗ trợ then chốt.");
            item.setArticleUrl("https://www.coindesk.com/news-" + i);
            item.setSource("CoinDesk");
            item.setSymbol("BTCUSDT");
            item.setBulletPointsVi("[\"Thị trường duy trì đà tích lũy tích cực.\",\"Dòng tiền tổ chức tiếp tục đổ vào các quỹ ETF.\"]");
            item.setSummaryPoints("[\"Thị trường duy trì đà tích lũy tích cực.\",\"Dòng tiền tổ chức tiếp tục đổ vào các quỹ ETF.\"]");
            item.setSentiment("NEUTRAL");
            item.setConfidencePct(BigDecimal.valueOf(80));
            item.setPublishedAt(LocalDateTime.now().minusHours(i));
            cachedArticles.add(item);
        }

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("BTCUSDT")))
                .thenReturn(cachedArticles);
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(cachedArticles);

        List<NewsFeedItemDto> result = aiNewsService.getLiveAiNewsFeed("BTCUSDT", 3);

        assertEquals(3, result.size(), "Hàm phải trả tối đa limit = 3 bài báo");
        assertTrue(result.get(0).isFromCache());
    }

    @Test
    @DisplayName("7. Mobile fallback MARKET trả tối đa limit đã chỉ định")
    public void testMobileFallbackMarketRespectsLimit() {
        List<NewsAiCache> marketArticles = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            NewsAiCache item = new NewsAiCache();
            item.setId((long) i);
            item.setTitle("Market Article " + i);
            item.setArticleUrl("https://example.com/market-" + i);
            item.setSymbol("MARKET");
            item.setSummaryPoints("[\"Market Summary " + i + "\"]");
            item.setSentiment("BULLISH");
            item.setConfidencePct(BigDecimal.valueOf(85));
            item.setPublishedAt(LocalDateTime.of(2026, 9, 8, 8, i));
            marketArticles.add(item);
        }

        // Khi tìm theo ETHUSDT thì rỗng
        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("ETHUSDT")))
                .thenReturn(List.of());
        // Fallback top 10 market articles
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(marketArticles);

        ResponseEntity<List<MobileNewsBundleResponse>> response =
                mobileSyncController.syncNewsForMobile("ETHUSDT", 2);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size(), "Mobile fallback MARKET phải trả chính xác tối đa limit = 2");
        assertEquals("NEWS_1", response.getBody().get(0).getNews().getNewsId());
    }

    @Test
    @DisplayName("8. Migration V6 chứa đầy đủ các cột metadata và tóm tắt gốc cho news_ai_cache")
    public void testMigrationV6ContainsRequiredColumns() throws Exception {
        java.io.File v6File = new java.io.File("src/main/resources/db/migration/V6__add_news_metadata_and_summary_fields.sql");
        assertTrue(v6File.exists(), "File V6 migration phải tồn tại");
        String sql = java.nio.file.Files.readString(v6File.toPath());
        assertTrue(sql.contains("author VARCHAR(255)"), "Phải có cột author");
        assertTrue(sql.contains("source VARCHAR(255)"), "Phải có cột source");
        assertTrue(sql.contains("original_summary TEXT"), "Phải có cột original_summary");
        assertTrue(sql.contains("banner_image VARCHAR(500)"), "Phải có cột banner_image");
        assertTrue(sql.contains("original_title VARCHAR(500)"), "Phải có cột original_title");
        assertTrue(sql.contains("IF NOT EXISTS"), "Phải đảm bảo tính idempotent với IF NOT EXISTS");
    }

    @Test
    @DisplayName("9. Cache lưu và bảo toàn toàn bộ metadata tác giả, nguồn, ảnh bìa, tóm tắt gốc")
    public void testCachePreservesFullMetadata() {
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(201L);
            return entity;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://example.com/nvda-earnings",
                "NVIDIA Reports Record Q3 Revenue",
                "NVDA",
                "[\"Doanh thu đạt mốc kỷ lục\", \"Nhu cầu chip AI tiếp tục bùng nổ\"]",
                "BULLISH",
                BigDecimal.valueOf(95),
                "Tăng trưởng vượt bậc",
                LocalDateTime.of(2026, 9, 9, 14, 0),
                LocalDateTime.of(2026, 9, 9, 14, 5),
                "Jane Doe",
                "Reuters",
                "NVIDIA reported record quarterly revenue driven by strong data center chip sales across global markets.",
                "https://example.com/img/nvda.jpg",
                "NVIDIA Reports Record Q3 Revenue"
        );

        assertTrue(saved.isPresent());
        NewsAiCache entity = saved.get();
        assertEquals("Jane Doe", entity.getAuthor());
        assertEquals("Reuters", entity.getSource());
        assertEquals("https://example.com/img/nvda.jpg", entity.getBannerImage());
        assertEquals("NVIDIA Reports Record Q3 Revenue", entity.getOriginalTitle());
        assertTrue(entity.getOriginalSummary().contains("data center chip sales"));
    }

    @Test
    @DisplayName("10. Bản ghi Cache cũ thiếu tác giả sẽ trả về null/rỗng, tuyệt đối không bịa đặt tên tác giả")
    public void testLegacyCacheWithoutAuthorDoesNotInventAuthor() {
        NewsAiCache legacyItem = new NewsAiCache();
        legacyItem.setId(301L);
        legacyItem.setTitle("Federal Reserve Interest Rate Decision");
        legacyItem.setDisplayTitleVi("Quyết định lãi suất của Cục Dự trữ Liên bang Fed");
        legacyItem.setArticleUrl("https://example.com/fed-decision");
        legacyItem.setSymbol("MARKET");
        legacyItem.setSummaryPoints("[\"Fed giữ nguyên lãi suất\", \"Thị trường kỳ vọng đợt cắt giảm cuối năm\"]");
        legacyItem.setSentiment("NEUTRAL");
        legacyItem.setConfidencePct(BigDecimal.valueOf(85));
        legacyItem.setPublishedAt(LocalDateTime.now().minusHours(2));
        legacyItem.setAuthor(null); // Không có tác giả trong cache cũ
        legacyItem.setSource(null);

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("MARKET")))
                .thenReturn(List.of(legacyItem));
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(List.of(legacyItem));

        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed("MARKET", 1);
        assertEquals(1, feed.size());
        NewsFeedItemDto dto = feed.get(0);

        assertNull(dto.getAuthor(), "Tác giả của cache cũ phải là null, không được tự ý gán là 'Financial News' hay 'Tin thị trường'");
    }

    @Test
    @DisplayName("11. NewsSummaryQualityPolicy phát hiện và làm sạch các câu boilerplate khuôn mẫu")
    public void testNewsSummaryQualityPolicyDetectsAndCleansBoilerplate() {
        List<String> legacyBoilerplate = List.of(
                "Trọng tâm tin tức: Apple ra mắt M4",
                "Tác động thị trường: Kỳ vọng dòng tiền tiếp tục gia tăng.",
                "Khuyến nghị FNMF: Theo dõi phản ứng giá tại các mốc hỗ trợ và kháng cự then chốt."
        );

        assertTrue(com.llmgateway.service.NewsSummaryQualityPolicy.hasBoilerplateBullets(legacyBoilerplate));

        String title = "Apple Unveils New M4 Chip For Pro Macs";
        String summary = "Apple today announced its latest M4 family of chips with significant neural engine upgrades. The new processors are built on second-generation 3nm technology and offer 50 percent faster CPU performance.";

        List<String> sanitized = com.llmgateway.service.NewsSummaryQualityPolicy.sanitizeBullets(legacyBoilerplate, title, summary);

        assertFalse(com.llmgateway.service.NewsSummaryQualityPolicy.hasBoilerplateBullets(sanitized));
        assertTrue(sanitized.size() >= 2 && sanitized.size() <= 4, "Số gạch đầu dòng phải từ 2 đến 4 ý");
        for (String bullet : sanitized) {
            assertFalse(bullet.startsWith("Trọng tâm tin tức:"));
            assertFalse(bullet.startsWith("Tác động thị trường:"));
            assertFalse(bullet.startsWith("Khuyến nghị FNMF:"));
        }
    }

    @Test
    @DisplayName("12. Phân tích Heuristic trích xuất 2-4 câu sự kiện thật, không sinh văn phong khuyến nghị giả")
    public void testHeuristicExtractsFactualSentences() {
        com.llmgateway.dto.news.NewsAnalysisRequest req = new com.llmgateway.dto.news.NewsAnalysisRequest(
                "Bitcoin Surges Past 70K on Institutional Inflows",
                "Bitcoin broke past the seventy thousand dollar mark on Monday as spot ETF inflows hit a three-month high. Major asset managers reported net positive subscriptions across all funds.",
                "BTCUSDT",
                "https://example.com/btc-70k"
        );

        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.findByTitle(anyString())).thenReturn(Optional.empty());

        com.llmgateway.dto.news.NewsAnalysisResponse res = aiNewsService.analyzeNews(req);

        assertNotNull(res);
        assertNotNull(res.getSummary());
        assertTrue(res.getSummary().size() >= 2 && res.getSummary().size() <= 4);
        for (String bullet : res.getSummary()) {
            assertFalse(bullet.startsWith("Trọng tâm tin tức:"));
            assertFalse(bullet.startsWith("Tác động thị trường:"));
            assertFalse(bullet.startsWith("Khuyến nghị FNMF:"));
        }
    }

    @Test
    @DisplayName("13. Endpoint /api/news/sync trả về bulletPoints từ 2 đến 4 ý và giữ đúng author/source")
    public void testSyncEndpointReturnsCorrectBulletsAndMetadata() {
        NewsAiCache item = new NewsAiCache();
        item.setId(401L);
        item.setTitle("Tesla Expands Supercharger Network");
        item.setDisplayTitleVi("Tesla mở rộng mạng lưới trạm sạc Supercharger");
        item.setArticleUrl("https://example.com/tesla-supercharger");
        item.setSymbol("TSLA");
        item.setSummaryPoints("[\"Tesla mở rộng thêm 500 trạm sạc mới\", \"Mạng lưới sạc hỗ trợ chuẩn NACS toàn cầu\"]");
        item.setSentiment("BULLISH");
        item.setConfidencePct(BigDecimal.valueOf(92));
        item.setPublishedAt(LocalDateTime.of(2026, 9, 9, 10, 0));
        item.setAuthor("Elon Team");
        item.setSource("Bloomberg");
        item.setBannerImage("https://example.com/tsla.jpg");
        item.setOriginalSummary("Tesla announced a nationwide expansion of its Supercharger network today, adding 500 ultra-fast stalls.");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("TSLA")))
                .thenReturn(List.of(item));
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(List.of(item));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        ResponseEntity<Map<String, Object>> response = controller.getSyncNewsFeed("TSLA", 5);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertEquals(1, data.size());

        Map<String, Object> first = data.get(0);
        assertEquals("Elon Team", first.get("author"));
        assertEquals("Bloomberg", first.get("source"));
        assertEquals("https://example.com/tsla.jpg", first.get("imageUrl"));
        List<String> bullets = (List<String>) first.get("bulletPoints");
        assertNotNull(bullets);
        assertTrue(bullets.size() >= 2 && bullets.size() <= 4);
    }

    @Test
    @DisplayName("14. Migration V7 chứa đầy đủ các cột bản địa hóa tiếng Việt (display_title_vi, bullet_points_vi)")
    public void testMigrationV7ContainsVietnameseLocalizationColumns() throws Exception {
        java.io.File v7File = new java.io.File("src/main/resources/db/migration/V7__add_news_vietnamese_localization_fields.sql");
        assertTrue(v7File.exists(), "File V7 migration phải tồn tại");
        String sql = java.nio.file.Files.readString(v7File.toPath());
        assertTrue(sql.contains("display_title_vi VARCHAR(500)"), "Phải có cột display_title_vi");
        assertTrue(sql.contains("bullet_points_vi TEXT"), "Phải có cột bullet_points_vi");
        assertTrue(sql.contains("IF NOT EXISTS"), "Phải đảm bảo tính idempotent với IF NOT EXISTS");
    }

    @Test
    @DisplayName("15. NewsPublisherResolver chuẩn hóa nhà xuất bản chính xác và loại bỏ các chuỗi generic")
    public void testNewsPublisherResolverAccurateResolution() {
        assertEquals("MarketBeat", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://www.marketbeat.com/articles/powering-ai/"));
        assertEquals("Yahoo Finance", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Financial News", "https://uk.finance.yahoo.com/news/netapp-beat-123.html"));
        assertEquals("CNBC", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Tin thị trường", "https://www.cnbc.com/2026/09/08/nvidia-supplier.html"));
        assertEquals("TipRanks", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Unknown", "https://www.tipranks.com/news/nvidia-board-member"));
        assertEquals("24/7 Wall St.", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("None", "https://247wallst.com/investing/2026/09/07/microsoft/"));
        assertEquals("Reuters", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Reuters", "https://reuters.com/business/finance"));
        assertEquals("Bloomberg", com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Bloomberg", null));

        assertTrue(com.llmgateway.service.NewsPublisherResolver.isGeneric("Financial News"));
        assertTrue(com.llmgateway.service.NewsPublisherResolver.isGeneric("Tin thị trường"));
        assertTrue(com.llmgateway.service.NewsPublisherResolver.isGeneric("Unknown"));
        assertFalse(com.llmgateway.service.NewsPublisherResolver.isGeneric("MarketBeat"));
    }

    @Test
    @DisplayName("16. NewsHeadlineTranslator dịch chuẩn tiếng Việt mà không biến dạng tickers, tên công ty hoặc số liệu")
    public void testNewsHeadlineTranslatorPreservesEntitiesAndNumbers() {
        String t1 = "NVIDIA (NVDA) Board Member Sells $410 Million of Company Stock";
        String tr1 = com.llmgateway.service.NewsHeadlineTranslator.translateHeadline(t1);
        assertTrue(tr1.contains("Thành viên HĐQT"));
        assertTrue(tr1.contains("NVDA"));
        assertTrue(tr1.contains("$410 Million"));

        String t2 = "HighTower Advisors LLC Raises Stake in Verizon Communications Inc. $VZ";
        String tr2 = com.llmgateway.service.NewsHeadlineTranslator.translateHeadline(t2);
        assertTrue(tr2.contains("tăng tỷ lệ sở hữu tại"));
        assertTrue(tr2.contains("VZ"));

        String t3 = "Bitcoin Surges Past $70K on Institutional Inflows";
        String tr3 = com.llmgateway.service.NewsHeadlineTranslator.translateHeadline(t3);
        assertTrue(tr3.contains("bứt phá vượt mốc"));
        assertTrue(tr3.contains("$70K"));
    }

    @Test
    @DisplayName("17. Cache lưu và phục hồi trọn vẹn displayTitleVi và bulletPointsVi")
    public void testCacheSavesAndRestoresVietnameseFields() {
        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(501L);
            return entity;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://www.marketbeat.com/articles/amd-ai-chip",
                "AMD Unveils Next-Gen AI Chip",
                "AMD",
                "[\"AMD công bố dòng chip AI mới\", \"Hiệu năng vượt trội trong xử lý dữ liệu lớn\"]",
                "BULLISH",
                BigDecimal.valueOf(95),
                "Động lực tăng trưởng mạnh",
                LocalDateTime.of(2026, 9, 9, 10, 0),
                LocalDateTime.of(2026, 9, 9, 10, 5),
                "Alex Vance",
                "Financial News", // Nguồn generic -> phải được resolve sang MarketBeat
                "AMD unveiled its next generation artificial intelligence accelerator.",
                "https://example.com/amd.jpg",
                "AMD Unveils Next-Gen AI Chip",
                "AMD ra mắt dòng chip AI thế hệ mới",
                "[\"AMD công bố dòng chip AI mới\", \"Hiệu năng vượt trội trong xử lý dữ liệu lớn\"]"
        );

        assertTrue(saved.isPresent());
        NewsAiCache entity = saved.get();
        assertEquals("MarketBeat", entity.getSource(), "Nguồn generic phải được tự động chuyển thành MarketBeat");
        assertEquals("AMD ra mắt dòng chip AI thế hệ mới", entity.getDisplayTitleVi());
        assertEquals("AMD Unveils Next-Gen AI Chip", entity.getOriginalTitle());
        assertNotNull(entity.getBulletPointsVi());
    }

    @Test
    @DisplayName("18. Endpoint /api/news/sync trả về displayTitleVi, publisher và bulletPointsVi không có generic")
    public void testSyncEndpointReturnsVietnameseLocalizationAndPublisher() {
        NewsAiCache item = new NewsAiCache();
        item.setId(601L);
        item.setTitle("Apple Reports Record Q3 Revenue");
        item.setDisplayTitleVi("Apple công bố doanh thu kỷ lục Q3");
        item.setOriginalTitle("Apple Reports Record Q3 Revenue");
        item.setArticleUrl("https://www.cnbc.com/apple-q3");
        item.setSymbol("AAPL");
        item.setSummaryPoints("[\"Doanh thu đạt mốc kỷ lục\", \"Dịch vụ tăng trưởng mạnh mẽ\"]");
        item.setBulletPointsVi("[\"Doanh thu đạt mốc kỷ lục\", \"Dịch vụ tăng trưởng mạnh mẽ\"]");
        item.setSentiment("BULLISH");
        item.setConfidencePct(BigDecimal.valueOf(95));
        item.setPublishedAt(LocalDateTime.of(2026, 9, 9, 12, 0));
        item.setAuthor("Tim Team");
        item.setSource("Financial News"); // Generic trong cache cũ
        item.setBannerImage("https://example.com/aapl.jpg");
        item.setOriginalSummary("Apple reported record revenue for the third fiscal quarter.");

        when(newsAiCacheRepository.findBySymbolOrderByPublishedAtDesc(eq("AAPL")))
                .thenReturn(List.of(item));
        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc())
                .thenReturn(List.of(item));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        ResponseEntity<Map<String, Object>> response = controller.getSyncNewsFeed("AAPL", 5);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertEquals(1, data.size());

        Map<String, Object> first = data.get(0);
        assertEquals("Apple công bố doanh thu kỷ lục Q3", first.get("title"));
        assertEquals("Apple công bố doanh thu kỷ lục Q3", first.get("displayTitleVi"));
        assertEquals("Apple Reports Record Q3 Revenue", first.get("originalTitle"));
        assertEquals("CNBC", first.get("source"), "Source generic phải được resolve thành CNBC");
        assertEquals("CNBC", first.get("publisher"));
        assertNotNull(first.get("bulletPointsVi"));
    }

    @Test
    @DisplayName("19. NewsLocalizationQualityPolicy kiểm tra chuẩn chất lượng tiếng Việt, phát hiện câu chưa dịch và boilerplate")
    public void testNewsLocalizationQualityPolicyRules() {
        // Tiêu đề
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(null, "English Title"));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("", "English Title"));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("Apple Reports Record Q3 Revenue", "Apple Reports Record Q3 Revenue"));
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("Some un-translated english title", "Some original title"));
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("Apple công bố doanh thu kỷ lục Q3", "Apple Reports Record Q3 Revenue"));
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi("NVIDIA mở vị thế $410 Million", "NVIDIA Takes $410M Position"));

        // Bullets
        List<String> validBullets = List.of(
                "Doanh thu đạt mức cao kỷ lục nhờ mảng dịch vụ",
                "Biên lợi nhuận gộp duy trì ổn định ở mức 45%",
                "Cổ tức được công bố tăng 5% cho các cổ đông"
        );
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(validBullets, "Apple công bố doanh thu kỷ lục Q3"));

        // Ít hơn 2 bullet -> không hợp lệ
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(List.of("Một ý duy nhất"), "Tiêu đề"));

        // Lặp tiêu đề -> không hợp lệ
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(
                List.of("Apple công bố doanh thu kỷ lục Q3", "Một ý hợp lệ khác"),
                "Apple công bố doanh thu kỷ lục Q3"
        ));

        // Boilerplate thừa -> không hợp lệ
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(
                List.of("Bài viết này nói về sự tăng trưởng", "Ý thứ hai"),
                "Tiêu đề"
        ));
    }

    @Test
    @DisplayName("20. NewsPublisherResolver không được tự bịa MarketBeat cho domain chưa biết hoặc URL null")
    public void testNewsPublisherResolverNoInventedPublisher() {
        // URL MarketBeat -> MarketBeat
        assertEquals("MarketBeat", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://www.marketbeat.com/stocks/NASDAQ/NVDA/"));
        // Yahoo Finance -> Yahoo Finance
        assertEquals("Yahoo Finance", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://finance.yahoo.com/news/123.html"));
        // Domain chưa biết -> dùng hostname đã làm sạch
        assertEquals("techcrunch.com", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://www.techcrunch.com/2026/09/08/ai-startup/"));
        assertEquals("custom-finance.org", com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "https://custom-finance.org/article/"));
        // URL null / sai định dạng -> trả null hoặc rỗng, TUYỆT ĐỐI KHÔNG trả MarketBeat
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, null));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher(null, "not-a-valid-url"));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Tin thị trường", null));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Financial News", ""));
        assertNull(com.llmgateway.service.NewsPublisherResolver.resolvePublisher("Unknown", "   "));
    }

    @Test
    @DisplayName("21. Bảo toàn originalTitle độc lập và không bị tráo đổi với displayTitleVi qua Cache round-trip")
    public void testOriginalTitleAndDisplayTitleViStrictIndependenceInCacheAndSync() {
        String originalEnglish = "Microsoft Signs Multi-Year AI Infrastructure Contract";
        String translatedVietnamese = "Microsoft ký hợp đồng hạ tầng AI nhiều năm";

        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache entity = i.getArgument(0);
            entity.setId(701L);
            return entity;
        });

        Optional<NewsAiCache> savedOpt = newsCacheService.saveCachedArticle(
                "https://www.reuters.com/technology/msft-ai-2026",
                originalEnglish,
                "MSFT",
                "[\"Hợp đồng cung cấp giải pháp đám mây\", \"Tối ưu hóa chi phí vận hành\"]",
                "BULLISH",
                BigDecimal.valueOf(92),
                "Hợp đồng lớn mở rộng thị phần AI",
                LocalDateTime.of(2026, 9, 8, 8, 30),
                LocalDateTime.of(2026, 9, 8, 8, 35),
                "John Doe",
                "Reuters",
                "Microsoft announced a major contract for infrastructure.",
                "https://example.com/msft.jpg",
                originalEnglish,
                translatedVietnamese,
                "[\"Hợp đồng cung cấp giải pháp đám mây\", \"Tối ưu hóa chi phí vận hành\"]"
        );

        assertTrue(savedOpt.isPresent());
        NewsAiCache cached = savedOpt.get();

        // Kiểm tra độc lập tuyệt đối giữa originalTitle và displayTitleVi
        assertEquals(originalEnglish, cached.getOriginalTitle(), "originalTitle phải giữ nguyên từng ký tự tiếng Anh gốc");
        assertEquals(translatedVietnamese, cached.getDisplayTitleVi(), "displayTitleVi phải là bản dịch tiếng Việt");
        assertNotEquals(cached.getOriginalTitle(), cached.getDisplayTitleVi(), "Hai trường phải hoàn toàn độc lập");
    }

    @Test
    @DisplayName("22. Bản ghi cache cũ thiếu displayTitleVi được làm giàu và cập nhật mà không xóa cache")
    public void testReEnrichLegacyCacheItemWhenAlphaReturnsSameUrl() {
        String url = "https://www.marketbeat.com/articles/nvda-split";
        String origTitle = "NVIDIA (NVDA) Announces Stock Split Effective Next Month";
        String newViTitle = "NVIDIA (NVDA) công bố chia tách cổ phiếu có hiệu lực từ tháng sau";

        NewsAiCache legacyEntity = new NewsAiCache();
        legacyEntity.setId(801L);
        legacyEntity.setArticleUrl(url);
        legacyEntity.setTitle(origTitle);
        legacyEntity.setOriginalTitle(origTitle);
        legacyEntity.setDisplayTitleVi(null); // Bản ghi cũ chưa có displayTitleVi
        legacyEntity.setSource("Financial News");

        when(newsAiCacheRepository.findByArticleUrl(eq(url))).thenReturn(Optional.of(legacyEntity));
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> i.getArgument(0));

        Optional<NewsAiCache> updated = newsCacheService.saveCachedArticle(
                url,
                origTitle,
                "NVDA",
                "[\"Kế hoạch chia tách cổ phiếu 10:1\", \"Tăng thanh khoản cho nhà đầu tư cá nhân\"]",
                "BULLISH",
                BigDecimal.valueOf(95),
                "Chia tách kích thích cầu",
                LocalDateTime.now(),
                LocalDateTime.now(),
                "Jane Doe",
                "MarketBeat",
                "Nvidia announced a stock split.",
                "https://example.com/nvda.jpg",
                origTitle,
                newViTitle,
                "[\"Kế hoạch chia tách cổ phiếu 10:1\", \"Tăng thanh khoản cho nhà đầu tư cá nhân\"]"
        );

        assertTrue(updated.isPresent());
        NewsAiCache result = updated.get();
        assertEquals(801L, result.getId(), "Giữ nguyên id của bản ghi cũ, không tạo record mới hoặc xóa cache");
        assertEquals(origTitle, result.getOriginalTitle(), "originalTitle giữ nguyên văn");
        assertEquals(newViTitle, result.getDisplayTitleVi(), "displayTitleVi được cập nhật thành công");
        assertEquals("MarketBeat", result.getSource(), "Nguồn được cập nhật từ MarketBeat");
    }

    @Test
    @DisplayName("23. originalSummary không bao giờ rò rỉ vào trường summary hiển thị trong /api/news/sync")
    public void testOriginalSummaryNeverLeaksIntoDisplaySummaryInSync() {
        String engTitle = "Apple Reports Record Q3 Services Revenue Amid iPhone Stagnation";
        String viTitle = "Apple công bố doanh thu mảng dịch vụ quý 3 đạt kỷ lục mới";
        String engSummary = "Apple announced third quarter fiscal results with strong services revenue growth of 14 percent year over year.";
        String viSummary = "Apple ghi nhận doanh thu mảng dịch vụ tăng trưởng 14% so với cùng kỳ, bù đắp sự chững lại của doanh số iPhone.";
        List<String> viBullets = List.of(
                "Doanh thu dịch vụ tăng 14% đạt mức cao kỷ lục.",
                "Biên lợi nhuận gộp toàn tập đoàn đạt mức tích cực.",
                "HĐQT cam kết duy trì chương trình mua lại cổ phiếu."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(901L);
        entity.setArticleUrl("https://www.cnbc.com/2026/09/08/apple-q3.html");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi("[\"" + String.join("\",\"", viBullets) + "\"]");
        entity.setSource("CNBC");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(90));
        entity.setReason("Tăng trưởng dịch vụ vững chắc");
        entity.setPublishedAt(LocalDateTime.of(2026, 9, 8, 14, 0));

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(null, 5);
        assertFalse(feed.isEmpty(), "Feed phải có ít nhất 1 bài từ cache");
        NewsFeedItemDto item = feed.get(0);

        assertEquals(engSummary, item.getOriginalSummary(), "originalSummary phải chứa nguyên văn tóm tắt tiếng Anh gốc");
        assertEquals(viSummary, item.getDisplaySummaryVi(), "displaySummaryVi phải chứa tóm tắt tiếng Việt");
        assertEquals(viSummary, item.getSummary(), "summary hiển thị tuyệt đối không phải là originalSummary tiếng Anh");
        assertFalse(item.getSummary().contains("announced third quarter fiscal results"), "summary không được chứa câu tiếng Anh từ originalSummary");
    }

    @Test
    @DisplayName("24. Các trường tương thích title/summary/bulletPoints đều là tiếng Việt 100%")
    public void testCompatibilityFieldsAreAllVietnamese() {
        String engTitle = "Federal Reserve Signals Potential September Rate Cut";
        String viTitle = "Cục Dự trữ Liên bang phát tín hiệu có thể hạ lãi suất trong tháng 9";
        String engSummary = "Fed Chair indicated policy makers are prepared to adjust monetary stance.";
        String viSummary = "Chủ tịch Fed cho biết các nhà hoạch định chính sách sẵn sàng điều chỉnh lập trường tiền tệ nếu lạm phát tiếp tục hạ nhiệt.";
        List<String> viBullets = List.of(
                "Fed theo dõi chặt chẽ dữ liệu thị trường lao động và lạm phát.",
                "Khả năng hạ lãi suất 25 điểm cơ bản trong phiên họp sắp tới.",
                "Thị trường tài chính phản ứng tích cực với phát biểu."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(902L);
        entity.setArticleUrl("https://www.reuters.com/markets/us/fed-rates-2026.html");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi("[\"" + String.join("\",\"", viBullets) + "\"]");
        entity.setSource("Reuters");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(88));
        entity.setPublishedAt(LocalDateTime.now());

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        List<NewsFeedItemDto> feed = aiNewsService.getLiveAiNewsFeed(null, 5);
        NewsFeedItemDto item = feed.get(0);

        // Trường tương thích
        assertEquals(item.getDisplayTitleVi(), item.getTitle(), "title tương thích phải bằng displayTitleVi");
        assertEquals(item.getDisplaySummaryVi(), item.getSummary(), "summary tương thích phải bằng displaySummaryVi");
        assertEquals(item.getBulletPointsVi(), item.getAiSummary(), "bulletPoints tương thích phải bằng bulletPointsVi");

        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(item.getTitle()));
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(item.getSummary()));
        for (String b : item.getBulletPointsVi()) {
            assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(b));
        }
    }

    @Test
    @DisplayName("25. Cache có tiêu đề Việt nhưng bullet hoặc summary tiếng Anh bị coi là chưa localized")
    public void testCacheWithEnglishBulletsOrSummaryRejectedByPolicy() {
        String viTitle = "Tesla mở rộng mạng lưới trạm sạc siêu nhanh tại châu Á";
        String engTitle = "Tesla Expands Supercharger Network in Asia";
        String engSummary = "Tesla announced ambitious charging infrastructure plans.";
        String viSummary = "Tesla công bố kế hoạch phát triển hạ tầng sạc xe điện tại các thị trường trọng điểm.";
        List<String> englishBullets = List.of(
                "Tesla expands superchargers across Asia.",
                "New fast charging standard adopted by local partners."
        );

        // Trường hợp 1: Bullets bằng tiếng Anh -> rejected
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, engTitle, viSummary, engSummary, englishBullets, "Reuters"
        ), "Bài có bullet tiếng Anh bắt buộc phải bị loại khỏi enrichedList");

        // Trường hợp 2: Summary bằng tiếng Anh (hoặc trùng originalSummary) -> rejected
        List<String> viBullets = List.of(
                "Mạng lưới trạm sạc mở rộng sang 5 quốc gia mới.",
                "Hợp tác với các đối tác hạ tầng nội địa."
        );
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, engTitle, engSummary, engSummary, viBullets, "Reuters"
        ), "Bài có summary tiếng Anh trùng originalSummary bắt buộc phải bị từ chối");

        // Trường hợp 3: Summary trùng title -> rejected
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, engTitle, viTitle, engSummary, viBullets, "Reuters"
        ), "Summary trùng lặp title phải bị từ chối");
    }

    @Test
    @DisplayName("26. Số lượng bullet points bắt buộc từ 2 đến 4, từ chối 1 hoặc 5 bullet")
    public void testBulletsCountStrictlyTwoToFour() {
        String viTitle = "Thị trường tiền điện tử phục hồi sau chuỗi ngày giảm giá";

        List<String> oneBullet = List.of("Giá Bitcoin vượt mốc 60.000 USD sau tín hiệu tích cực.");
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(oneBullet, viTitle), "1 bullet phải bị từ chối");

        List<String> fiveBullets = List.of(
                "Bitcoin tăng trưởng mạnh mẽ trở lại.",
                "Dòng tiền tổ chức tiếp tục gia nhập thị trường.",
                "Chỉ số sợ hãi và tham lam chuyển sang tích cực.",
                "Khối lượng giao dịch trên các sàn tăng 30%.",
                "Ý thứ năm vượt quá giới hạn cho phép."
        );
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(fiveBullets, viTitle), "5 bullet phải bị từ chối");

        List<String> validThreeBullets = List.of(
                "Bitcoin phục hồi vượt vùng kháng cự ngắn hạn.",
                "Dòng tiền giải ngân ổn định trở lại.",
                "Tâm lý nhà đầu tư dần được cải thiện."
        );
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(validThreeBullets, viTitle), "3 bullet hợp lệ phải được chấp nhận");
    }

    @Test
    @DisplayName("27. MockMvc kiểm tra hợp đồng thật của /api/news/sync với đầy đủ 12 production keys")
    public void testMockMvcNewsSyncReturnsExactProductionContract() throws Exception {
        String engTitle = "Gold Prices Surge to Historic High on Safe-Haven Demand";
        String viTitle = "Giá vàng tăng vọt lên mức kỷ lục lịch sử do nhu cầu trú ẩn an toàn";
        String engSummary = "Spot gold rose 1.5 percent to reach all-time high as geopolitical tensions escalated.";
        String viSummary = "Giá vàng giao ngay tăng 1,5% chạm đỉnh lịch sử mới khi căng thẳng địa chính trị thúc đẩy dòng vốn tìm nơi trú ẩn an toàn.";
        List<String> viBullets = List.of(
                "Giá vàng quốc tế xác lập kỷ lục cao nhất mọi thời đại.",
                "Lợi suất trái phiếu giảm hỗ trợ đà tăng của kim loại quý.",
                "Quỹ ETF vàng toàn cầu ghi nhận tuần mua ròng thứ ba liên tiếp."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(903L);
        entity.setArticleUrl("https://www.bloomberg.com/markets/commodities/gold-record-2026.html");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi(objectMapper.writeValueAsString(viBullets));
        entity.setSource("Bloomberg");
        entity.setAuthor("Robert Smith");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(94));
        entity.setPublishedAt(LocalDateTime.of(2026, 9, 8, 10, 30));

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        org.springframework.test.web.servlet.MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ok"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].originalTitle").value(engTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].originalSummary").value(engSummary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].displayTitleVi").value(viTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].displaySummaryVi").value(viSummary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].bulletPointsVi[0]").value(viBullets.get(0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].publisher").value("Bloomberg"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].author").value("Robert Smith"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].title").value(viTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].summary").value(viSummary))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].bulletPoints[0]").value(viBullets.get(0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].sentiment").value("bullish"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].confidence").value(94));
    }

    @Test
    @DisplayName("28. Cache round-trip bảo toàn trọn vẹn cả dữ liệu gốc và dữ liệu bản địa hóa")
    public void testCacheRoundTripPreservesOriginalAndLocalizedData() {
        String origTitle = "JPMorgan Upgrades Semiconductor Sector Outlook for 2027";
        String origSummary = "Analysts raised price targets across leading chipmakers citing persistent data center demand.";
        String viTitle = "JPMorgan nâng triển vọng ngành bán dẫn cho năm 2027";
        String viSummary = "Các chuyên gia phân tích nâng giá mục tiêu cho nhóm cổ phiếu chip trước nhu cầu trung tâm dữ liệu tăng bền vững.";
        String viBulletsJson = "[\"Nâng triển vọng ngành bán dẫn lên mức khả quan.\",\"Nhu cầu chip trung tâm dữ liệu tăng trưởng vượt dự báo.\"]";

        when(newsAiCacheRepository.findByArticleUrl(anyString())).thenReturn(Optional.empty());
        when(newsAiCacheRepository.save(any(NewsAiCache.class))).thenAnswer(i -> {
            NewsAiCache c = i.getArgument(0);
            c.setId(999L);
            return c;
        });

        Optional<NewsAiCache> saved = newsCacheService.saveCachedArticle(
                "https://finance.yahoo.com/news/jpmorgan-chips.html",
                origTitle,
                "SOXX",
                viBulletsJson,
                "BULLISH",
                BigDecimal.valueOf(91),
                "Triển vọng bán dẫn tích cực",
                LocalDateTime.now(),
                LocalDateTime.now(),
                "Analyst Team",
                "Yahoo Finance",
                origSummary,
                "https://example.com/chip.jpg",
                origTitle,
                viTitle,
                viSummary,
                viBulletsJson
        );

        assertTrue(saved.isPresent());
        NewsAiCache cached = saved.get();
        assertEquals(origTitle, cached.getOriginalTitle());
        assertEquals(origSummary, cached.getOriginalSummary());
        assertEquals(viTitle, cached.getDisplayTitleVi());
        assertEquals(viSummary, cached.getDisplaySummaryVi());
        assertEquals(viBulletsJson, cached.getBulletPointsVi());
        assertEquals("Yahoo Finance", cached.getSource());
    }

    @Test
    @DisplayName("29. Tiêu đề pha tiếng Anh dù có chữ tiếng Việt vẫn phải bị từ chối")
    public void testRejectsMixedEnglishVietnameseTitle() {
        String mixedTitle = "Nvidia reports quarterly revenue và earnings growth";
        String origTitle = "Nvidia reports quarterly revenue and earnings growth";
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(mixedTitle, origTitle),
                "Tiêu đề pha tiếng Anh 'Nvidia reports quarterly revenue và earnings growth' phải bị từ chối");
    }

    @Test
    @DisplayName("30. Tiêu đề chứa allowlist doanh nghiệp/ticker hợp lệ và thuần tiếng Việt phải được chấp nhận")
    public void testAcceptsProperNamesAndBusinessAllowlist() {
        String origTitle1 = "Nvidia reports strong quarterly revenue";
        String viTitle1 = "Nvidia công bố doanh thu quý tăng trưởng mạnh";
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(viTitle1, origTitle1),
                "Nvidia là allowlist và các từ còn lại tiếng Việt chuẩn phải được chấp nhận");

        String origTitle2 = "Bitcoin ETFs record 500 million USD in capital inflows";
        String viTitle2 = "Bitcoin ETF ghi nhận dòng vốn 500 triệu USD";
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidDisplayTitleVi(viTitle2, origTitle2),
                "Bitcoin ETF ghi nhận dòng vốn 500 triệu USD phải được chấp nhận");
    }

    @Test
    @DisplayName("31. Bullet pha phần lớn tiếng Anh phải bị từ chối")
    public void testRejectsMixedEnglishBullets() {
        String viTitle = "Nvidia công bố doanh thu quý tăng trưởng mạnh";
        List<String> mixedBullets = List.of(
                "Doanh thu data center surged 122% year over year.",
                "Nhu cầu chip trí tuệ nhân tạo tiếp tục ở mức cao."
        );
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isValidBullets(mixedBullets, viTitle),
                "Bullet pha phần lớn tiếng Anh phải bị từ chối");
    }

    @Test
    @DisplayName("32. Publisher là metadata tùy chọn: null hoặc rỗng vẫn cho phép bài hợp lệ đi qua")
    public void testOptionalPublisherBehavior() {
        String origTitle = "Apple reports earnings";
        String origSummary = "Apple reported record quarterly revenue.";
        String viTitle = "Apple công bố kết quả kinh doanh quý kỷ lục";
        String viSummary = "Tập đoàn Apple vừa ghi nhận kết quả kinh doanh quý vượt xa mọi kỳ vọng của giới đầu tư.";
        List<String> viBullets = List.of(
                "Doanh thu mảng dịch vụ tiếp tục thiết lập mốc kỷ lục mới.",
                "Sức mua thiết bị tại thị trường quốc tế duy trì đà hồi phục."
        );

        // Publisher null -> HỢP LỆ
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, origTitle, viSummary, origSummary, viBullets, null
        ), "Publisher null vẫn cho phép bài hợp lệ đi qua");

        // Publisher rỗng -> HỢP LỆ
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, origTitle, viSummary, origSummary, viBullets, ""
        ), "Publisher rỗng vẫn cho phép bài hợp lệ đi qua");

        // Publisher uy tín -> HỢP LỆ
        assertTrue(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, origTitle, viSummary, origSummary, viBullets, "MarketBeat"
        ), "Publisher MarketBeat phải hợp lệ");

        // Publisher generic trực tiếp -> BỊ TỪ CHỐI nếu chưa sanitize
        assertFalse(com.llmgateway.service.NewsLocalizationQualityPolicy.isFullyLocalized(
                viTitle, origTitle, viSummary, origSummary, viBullets, "Financial News"
        ), "Publisher Financial News generic trực tiếp phải bị từ chối");
    }

    @Test
    @DisplayName("33. MockMvc xác minh bài viết thiếu publisher vẫn xuất hiện trong response")
    public void testMockMvcPreservesArticleWhenPublisherMissing() throws Exception {
        String engTitle = "Tesla Expands Supercharger Network Globally";
        String viTitle = "Tesla mở rộng mạng lưới trạm sạc nhanh trên toàn cầu";
        String engSummary = "Tesla announced deployment of new charging stations.";
        String viSummary = "Tập đoàn Tesla vừa công bố kế hoạch mở rộng mạnh mẽ các trạm sạc nhanh trên toàn cầu.";
        List<String> viBullets = List.of(
                "Mạng lưới trạm sạc được phủ sóng thêm tại nhiều quốc gia.",
                "Công nghệ sạc nhanh thế hệ mới giúp rút ngắn thời gian sạc."
        );

        NewsAiCache entity = new NewsAiCache();
        entity.setId(904L);
        entity.setArticleUrl("");
        entity.setTitle(engTitle);
        entity.setOriginalTitle(engTitle);
        entity.setDisplayTitleVi(viTitle);
        entity.setOriginalSummary(engSummary);
        entity.setDisplaySummaryVi(viSummary);
        entity.setBulletPointsVi(objectMapper.writeValueAsString(viBullets));
        entity.setSource(""); // Không có publisher
        entity.setAuthor("");
        entity.setSentiment("BULLISH");
        entity.setConfidencePct(BigDecimal.valueOf(88));
        entity.setPublishedAt(LocalDateTime.of(2026, 9, 8, 11, 0));

        when(newsAiCacheRepository.findTop10ByOrderByPublishedAtDesc()).thenReturn(List.of(entity));

        com.llmgateway.controller.NewsAiController controller = new com.llmgateway.controller.NewsAiController(aiNewsService);
        org.springframework.test.web.servlet.MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/sync"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ok"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].displayTitleVi").value(viTitle))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].publisher").value(""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].source").value(""));
    }
}
