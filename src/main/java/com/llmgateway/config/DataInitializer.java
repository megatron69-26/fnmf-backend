package com.llmgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final AuthService authService;
    private final NewsAiCacheRepository newsAiCacheRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    public DataInitializer(UserRepository userRepository, AuthService authService,
                           NewsAiCacheRepository newsAiCacheRepository,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           ResourceLoader resourceLoader) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.newsAiCacheRepository = newsAiCacheRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(String... args) {
        // 1. Kiểm tra và di chuyển kiểu dữ liệu OID sang TEXT trên PostgreSQL nếu bảng cũ bị map @Lob
        migratePostgresLobColumnsIfNeeded();

        // 2. Khởi tạo tài khoản mặc định
        initializeDefaultUser();

        // 3. Khởi tạo / nạp seed cache bài báo thật nếu cache đang trống
        seedNewsCacheIfNeeded();
    }

    private void migratePostgresLobColumnsIfNeeded() {
        try {
            List<String> oidCols = jdbcTemplate.query(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE lower(table_name) = 'news_ai_cache' " +
                    "AND lower(column_name) IN ('summary_points', 'reason') " +
                    "AND data_type = 'oid'",
                    (rs, rowNum) -> rs.getString("column_name")
            );
            if (!oidCols.isEmpty()) {
                log.info("Phát hiện cột OID trên news_ai_cache: {}. Đang chuyển đổi sang TEXT...", oidCols);
                jdbcTemplate.execute("TRUNCATE TABLE news_ai_cache");
                jdbcTemplate.execute("ALTER TABLE news_ai_cache ALTER COLUMN summary_points TYPE TEXT");
                jdbcTemplate.execute("ALTER TABLE news_ai_cache ALTER COLUMN reason TYPE TEXT");
                log.info("Chuyển đổi thành công schema news_ai_cache sang TEXT trên PostgreSQL!");
            }
        } catch (Exception e) {
            log.debug("Bỏ qua kiểm tra cột OID: {}", e.getMessage());
        }
    }

    private void initializeDefaultUser() {
        try {
            if (userRepository.count() == 0) {
                log.info(">>> Khởi tạo CSDL tự động: Đang tạo tài khoản mặc định khoi.pro@fnmf.com...");
                RegisterRequest req = new RegisterRequest();
                req.setFullName("Đặng Đức Khôi");
                req.setEmail("khoi.pro@fnmf.com");
                req.setPassword("mypassword123");
                authService.register(req);
                log.info(">>> Đã tạo thành công tài khoản 'khoi.pro@fnmf.com' kèm ví $10,000 USD!");
            }
        } catch (Exception e) {
            log.warn("CSDL đã có dữ liệu hoặc không cần khởi tạo user: {}", e.getMessage());
        }
    }

    private void seedNewsCacheIfNeeded() {
        try {
            long currentCount = newsAiCacheRepository.count();
            if (currentCount >= 5) {
                log.info("CSDL Cache đã có {} bài báo, không cần nạp seed.", currentCount);
                return;
            }

            Resource resource = resourceLoader.getResource("classpath:news_cache_seed.json");
            if (!resource.exists()) {
                log.warn("news_cache_seed.json không tồn tại trên classpath.");
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                List<Map<String, Object>> items = objectMapper.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
                List<NewsAiCache> toSave = new ArrayList<>();
                for (Map<String, Object> map : items) {
                    String url = (String) map.get("articleUrl");
                    if (url == null || url.isBlank() || newsAiCacheRepository.findByArticleUrl(url).isPresent()) {
                        continue;
                    }
                    NewsAiCache entity = new NewsAiCache();
                    entity.setArticleUrl(url);
                    entity.setTitle((String) map.get("title"));
                    entity.setSymbol(map.get("symbol") != null ? (String) map.get("symbol") : "MARKET");
                    entity.setSummaryPoints((String) map.get("summaryPoints"));
                    entity.setSentiment((String) map.get("sentiment"));
                    if (map.get("confidencePct") != null) {
                        entity.setConfidencePct(new BigDecimal(map.get("confidencePct").toString()));
                    }
                    entity.setReason((String) map.get("reason"));
                    entity.setPublishedAt(LocalDateTime.now());
                    entity.setAnalyzedAt(LocalDateTime.now());
                    toSave.add(entity);
                }
                if (!toSave.isEmpty()) {
                    newsAiCacheRepository.saveAll(toSave);
                    log.info(">>> Đã nạp thành công {} bài báo thật vào cache CSDL từ file seed!", toSave.size());
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi nạp seed cache tin tức: {}", e.getMessage());
        }
    }
}
