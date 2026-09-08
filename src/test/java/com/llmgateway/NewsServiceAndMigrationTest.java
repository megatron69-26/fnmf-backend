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
}
