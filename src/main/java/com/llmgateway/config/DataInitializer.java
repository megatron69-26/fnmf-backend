package com.llmgateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AuthService;
import com.llmgateway.service.NewsCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final AuthService authService;
    private final NewsCacheService newsCacheService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    public DataInitializer(UserRepository userRepository, AuthService authService,
                           NewsCacheService newsCacheService,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           ResourceLoader resourceLoader) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.newsCacheService = newsCacheService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(String... args) {
        // 1. Kiểm tra an toàn và di chuyển cột OID sang TEXT trên PostgreSQL nếu cần
        migratePostgresLobColumnsIfNeeded();

        // 2. Khởi tạo tài khoản mặc định
        initializeDefaultUser();

        // 3. Nạp seed cache bài báo thật nếu cache đang thiếu dữ liệu
        seedNewsCacheIfNeeded();
    }

    /**
     * Migration an toàn, idempotent cho PostgreSQL:
     * - Chỉ thực hiện khi database product là PostgreSQL.
     * - Kiểm tra các cột summary_points / reason có kiểu 'oid' hay không.
     * - Tuyệt đối KHÔNG TRUNCATE nếu bảng có dữ liệu.
     * - Nếu bảng có dữ liệu và không thể bảo toàn LOB, dừng migration và ghi log cảnh báo; KHÔNG XÓA DỮ LIỆU.
     */
    public boolean migratePostgresLobColumnsIfNeeded() {
        if (!isPostgreSql()) {
            log.info("Database hiện tại không phải PostgreSQL, bỏ qua migration OID sang TEXT.");
            return true;
        }

        try {
            // Kiểm tra bảng tồn tại
            Integer tableCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = 'news_ai_cache'",
                    Integer.class
            );
            if (tableCount == null || tableCount == 0) {
                log.info("Bảng news_ai_cache chưa tồn tại, schema chuẩn TEXT sẽ được khởi tạo tự động.");
                return true;
            }

            // Tìm các cột có data_type = 'oid'
            List<String> oidCols = jdbcTemplate.query(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE lower(table_name) = 'news_ai_cache' " +
                    "AND lower(column_name) IN ('summary_points', 'reason') " +
                    "AND lower(data_type) = 'oid'",
                    (rs, rowNum) -> rs.getString("column_name")
            );

            if (oidCols.isEmpty()) {
                log.info("Các cột trên news_ai_cache đã là kiểu TEXT/chuẩn, không có cột OID.");
                return true;
            }

            log.info("Phát hiện cột kiểu OID trên news_ai_cache: {}. Đang kiểm tra dữ liệu trước khi migration...", oidCols);

            Long rowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM news_ai_cache", Long.class);
            if (rowCount == null) rowCount = 0L;

            if (rowCount == 0) {
                // Bảng rỗng -> An toàn 100% để ALTER COLUMN trực tiếp sang TEXT
                log.info("Bảng news_ai_cache rỗng (0 bản ghi), chuyển đổi cột sang TEXT an toàn...");
                for (String col : oidCols) {
                    jdbcTemplate.execute("ALTER TABLE news_ai_cache ALTER COLUMN " + col + " TYPE TEXT");
                }
                log.info("Đã chuyển đổi thành công schema news_ai_cache sang TEXT trên PostgreSQL!");
                return true;
            }

            // Bảng CÓ dữ liệu: Thử bảo toàn dữ liệu
            log.warn("Bảng news_ai_cache đang chứa {} bản ghi có cột kiểu OID. Tiến hành kiểm tra khả năng bảo toàn LOB...", rowCount);
            boolean canSafelyPreserve = false;
            try {
                // Kiểm tra xem có thể đọc LOB an toàn không
                Integer testCount = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM news_ai_cache WHERE summary_points IS NOT NULL AND lo_get(summary_points::oid) IS NOT NULL",
                        Integer.class
                );
                canSafelyPreserve = (testCount != null && testCount > 0);
            } catch (Exception e) {
                log.warn("Không thể truy xuất LOB stream qua lo_get: {}. Dừng chuyển đổi để bảo toàn dữ liệu.", e.getMessage());
                canSafelyPreserve = false;
            }

            if (canSafelyPreserve) {
                for (String col : oidCols) {
                    jdbcTemplate.execute(
                            "ALTER TABLE news_ai_cache ALTER COLUMN " + col + " TYPE TEXT USING (" +
                            "CASE WHEN " + col + " IS NOT NULL AND (" + col + ")::text ~ '^[0-9]+$' " +
                            "THEN convert_from(lo_get((" + col + ")::oid), 'UTF8') " +
                            "ELSE (" + col + ")::text END)"
                    );
                }
                log.info("Đã migrate bảo toàn dữ liệu thành công các cột {} sang TEXT trên PostgreSQL!", oidCols);
                return true;
            } else {
                log.warn("DỪNG MIGRATION: Bảng news_ai_cache có {} bản ghi nhưng không thể bảo toàn LOB an toàn. Dữ liệu được bảo lưu nguyên vẹn, tuyệt đối KHÔNG XÓA hay TRUNCATE.", rowCount);
                return false;
            }
        } catch (Exception e) {
            log.error("Lỗi xảy ra trong quá trình migration PostgreSQL OID sang TEXT: {}", e.getMessage(), e);
            throw new RuntimeException("Migration PostgreSQL OID sang TEXT thất bại: " + e.getMessage(), e);
        }
    }

    private boolean isPostgreSql() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) conn -> {
                String prodName = conn.getMetaData().getDatabaseProductName();
                return prodName != null && prodName.equalsIgnoreCase("PostgreSQL");
            }));
        } catch (Exception e) {
            log.warn("Không thể kiểm tra loại database qua connection metadata: {}", e.getMessage());
            return false;
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

    /**
     * Nạp seed dữ liệu thật từ file JSON vào CSDL Cache:
     * - Chỉ chạy khi cache thiếu dữ liệu (< 5 bản ghi).
     * - Giữ nguyên publishedAt và analyzedAt thật từ file seed.
     * - Không gán LocalDateTime.now() cho publishedAt.
     * - Bỏ qua bản ghi nếu timestamp lỗi; không tạo ngày giả.
     * - Không dùng ID cũ; để PostgreSQL tự sinh Identity.
     * - Chống trùng lặp theo articleUrl.
     * - Không ghi đè các bài báo đang tồn tại.
     */
    public void seedNewsCacheIfNeeded() {
        try {
            long currentCount = newsCacheService.count();
            if (currentCount >= 5) {
                log.info("CSDL Cache đã có {} bài báo, không cần nạp seed.", currentCount);
                return;
            }

            Resource resource = resourceLoader.getResource("classpath:news_cache_seed.json");
            if (!resource.exists()) {
                log.warn("news_cache_seed.json không tồn tại trên classpath.");
                return;
            }

            List<Map<String, Object>> items;
            try (InputStream is = resource.getInputStream()) {
                items = objectMapper.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
            }

            if (items == null || items.isEmpty()) {
                log.info("File news_cache_seed.json rỗng.");
                return;
            }

            Set<String> processedUrls = new HashSet<>();
            List<NewsAiCache> toSave = new ArrayList<>();

            for (Map<String, Object> map : items) {
                String url = (String) map.get("articleUrl");
                if (url == null || url.isBlank()) {
                    log.warn("Bỏ qua bài báo thiếu articleUrl trong seed.");
                    continue;
                }
                url = url.trim();

                // Chống trùng lặp ngay trong danh sách nạp seed
                if (!processedUrls.add(url)) {
                    continue;
                }

                // Không ghi đè bài báo đã có sẵn trong CSDL
                if (newsCacheService.existsByArticleUrl(url)) {
                    continue;
                }

                // Parse publishedAt thật, nếu lỗi thì bỏ qua bản ghi (không tạo ngày giả)
                Object pubRaw = map.get("publishedAt");
                if (pubRaw == null || pubRaw.toString().isBlank()) {
                    log.warn("Bỏ qua bài báo '{}' do thiếu trường publishedAt.", map.get("title"));
                    continue;
                }
                LocalDateTime pubDate;
                try {
                    pubDate = LocalDateTime.parse(pubRaw.toString().trim());
                } catch (Exception e) {
                    log.warn("Không thể parse publishedAt '{}' cho bài '{}': {}. Bỏ qua bài báo.",
                            pubRaw, map.get("title"), e.getMessage());
                    continue;
                }

                // Parse analyzedAt thật (nếu có, không thì lấy pubDate)
                LocalDateTime analyzedDate = pubDate;
                Object analyzedRaw = map.get("analyzedAt");
                if (analyzedRaw != null && !analyzedRaw.toString().isBlank()) {
                    try {
                        analyzedDate = LocalDateTime.parse(analyzedRaw.toString().trim());
                    } catch (Exception e) {
                        analyzedDate = pubDate;
                    }
                }

                NewsAiCache entity = new NewsAiCache();
                entity.setId(null); // Để PostgreSQL tự sinh Identity
                entity.setArticleUrl(url);
                entity.setTitle(map.get("title") != null ? map.get("title").toString().trim() : "");
                entity.setSymbol(map.get("symbol") != null ? map.get("symbol").toString().trim().toUpperCase() : "MARKET");
                entity.setSummaryPoints((String) map.get("summaryPoints"));
                entity.setSentiment(map.get("sentiment") != null ? map.get("sentiment").toString().trim() : "NEUTRAL");

                if (map.get("confidencePct") != null) {
                    try {
                        entity.setConfidencePct(new BigDecimal(map.get("confidencePct").toString().trim()));
                    } catch (Exception e) {
                        entity.setConfidencePct(BigDecimal.valueOf(80));
                    }
                } else {
                    entity.setConfidencePct(BigDecimal.valueOf(80));
                }

                entity.setReason(map.get("reason") != null ? map.get("reason").toString() : "");
                entity.setPublishedAt(pubDate);
                entity.setAnalyzedAt(analyzedDate);

                toSave.add(entity);
            }

            if (!toSave.isEmpty()) {
                newsCacheService.saveAll(toSave);
                log.info(">>> Đã nạp thành công {} bài báo thật vào cache CSDL từ file seed (giữ nguyên publishedAt thật, không trùng lặp)!", toSave.size());
            }
        } catch (Exception e) {
            log.error("Lỗi khi nạp seed cache tin tức: {}", e.getMessage(), e);
        }
    }
}
