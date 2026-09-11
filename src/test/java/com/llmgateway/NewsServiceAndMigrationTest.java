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
            item.setTitle("News Article " + i);
            item.setArticleUrl("https://example.com/news-" + i);
            item.setSymbol("BTCUSDT");
            item.setSummaryPoints("[\"Summary " + i + "\"]");
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
}
