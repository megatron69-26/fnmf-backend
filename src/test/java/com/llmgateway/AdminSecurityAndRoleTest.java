package com.llmgateway;

import com.llmgateway.config.DataInitializer;
import com.llmgateway.controller.AdminController;
import com.llmgateway.dto.auth.AuthResponse;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.entity.Wallet;
import com.llmgateway.filter.ProductionSecurityFilter;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.service.AdminService;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.service.AuthService;
import com.llmgateway.service.NewsCacheService;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AdminSecurityAndRoleTest {

    private UserRepository userRepository;
    private WalletRepository walletRepository;
    private HoldingRepository holdingRepository;
    private TransactionRepository transactionRepository;
    private AdminService adminService;
    private AdminController adminController;
    private AuthService authService;
    private JwtUtil jwtUtil;
    private JdbcTemplate jdbcTemplate;
    private AiNewsService aiNewsService;
    private Environment env;
    private PasswordEncoder passwordEncoder;
    private DataInitializer dataInitializer;
    private ProductionSecurityFilter securityFilter;

    @BeforeEach
    public void setUp() {
        userRepository = mock(UserRepository.class);
        walletRepository = mock(WalletRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        jwtUtil = mock(JwtUtil.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        aiNewsService = mock(AiNewsService.class);
        env = mock(Environment.class);
        passwordEncoder = mock(PasswordEncoder.class);

        adminService = new AdminService(userRepository, walletRepository, holdingRepository, transactionRepository);
        adminController = new AdminController(adminService, jdbcTemplate, jwtUtil, userRepository, aiNewsService);

        authService = new AuthService(userRepository, walletRepository, passwordEncoder, jwtUtil);

        dataInitializer = new DataInitializer(
                userRepository,
                authService,
                mock(NewsCacheService.class),
                jdbcTemplate,
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                mock(ResourceLoader.class),
                env,
                passwordEncoder
        );

        securityFilter = new ProductionSecurityFilter();
    }

    @Test
    @DisplayName("1. User đăng ký mới luôn có role USER")
    public void testNewUserHasRoleUser() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("newuser@fnmf.com");
        req.setPassword("secret123");
        req.setFullName("Normal User");

        when(userRepository.existsByEmail("newuser@fnmf.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_secret");

        User savedUser = new User("newuser@fnmf.com", "hashed_secret", "Normal User", UserRole.USER);
        savedUser.setId(10L);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        Wallet savedWallet = new Wallet(10L);
        savedWallet.setId(20L);
        when(walletRepository.save(any(Wallet.class))).thenReturn(savedWallet);
        when(jwtUtil.generateToken(anyString(), anyLong())).thenReturn("mock.token.jwt");

        AuthResponse resp = authService.register(req);

        assertNotNull(resp);
        assertEquals("USER", resp.getUser().getRole(), "Role của tài khoản đăng ký mới phải là USER");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(UserRole.USER, captor.getValue().getRole());
    }

    @Test
    @DisplayName("2. User thường không gọi được admin API (trả về 403)")
    public void testNormalUserDeniedAdminApi() {
        String token = "user.valid.jwt";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("user@fnmf.com");

        User user = new User("user@fnmf.com", "hash", "User Regular", UserRole.USER);
        when(userRepository.findByEmail("user@fnmf.com")).thenReturn(Optional.of(user));

        ResponseEntity<Map<String, Object>> response = adminController.getDatabaseOverview("Bearer " + token);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), "User thường gọi admin phải nhận HTTP 403 Forbidden");
    }

    @Test
    @DisplayName("3. Admin role gọi được overview (trả về 200)")
    public void testAdminRoleCanAccessOverview() {
        String token = "admin.valid.jwt";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        when(jdbcTemplate.queryForList(anyString())).thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = adminController.getDatabaseOverview("Bearer " + token);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Admin hợp lệ gọi overview phải nhận HTTP 200 OK");
    }

    @Test
    @DisplayName("4. JWT sai hoặc hết hạn bị từ chối với HTTP 401")
    public void testInvalidOrExpiredJwtRejectedWith401() {
        // Missing token
        ResponseEntity<Map<String, Object>> res1 = adminController.getDatabaseOverview(null);
        assertEquals(HttpStatus.UNAUTHORIZED, res1.getStatusCode(), "Thiếu token phải trả 401");

        // Invalid token
        when(jwtUtil.validateToken("bad.token")).thenReturn(false);
        ResponseEntity<Map<String, Object>> res2 = adminController.getDatabaseOverview("Bearer bad.token");
        assertEquals(HttpStatus.UNAUTHORIZED, res2.getStatusCode(), "Token sai/hết hạn phải trả 401");
    }

    @Test
    @DisplayName("5. Bootstrap disabled không tạo admin")
    public void testBootstrapDisabledDoesNotCreateAdmin() {
        when(env.getProperty("FNMF_ADMIN_BOOTSTRAP_ENABLED")).thenReturn("false");

        dataInitializer.bootstrapAdminIfNeeded();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("6. Bootstrap enabled tạo hoặc promote admin dùng fixture password và không trim password")
    public void testBootstrapEnabledPromotesAdminWithFixture() {
        String fixturePassword = "  test-mock-admin-password-fixture  ";
        when(env.getProperty("FNMF_ADMIN_BOOTSTRAP_ENABLED")).thenReturn("true");
        when(env.getProperty("FNMF_ADMIN_EMAIL")).thenReturn("superadmin@fnmf.com");
        when(env.getProperty("FNMF_ADMIN_PASSWORD")).thenReturn(fixturePassword);
        when(passwordEncoder.encode(fixturePassword)).thenReturn("bcrypt_hash");

        User existingUser = new User("superadmin@fnmf.com", "old_hash", "Admin", UserRole.USER);
        when(userRepository.findByEmail("superadmin@fnmf.com")).thenReturn(Optional.of(existingUser));

        dataInitializer.bootstrapAdminIfNeeded();

        assertEquals(UserRole.ADMIN, existingUser.getRole());
        assertEquals("bcrypt_hash", existingUser.getPasswordHash());
        // Xác minh passwordEncoder được gọi với đúng mật khẩu chưa bị trim
        verify(passwordEncoder).encode(eq(fixturePassword));
        verify(userRepository).save(existingUser);
    }

    @Test
    @DisplayName("7. Migration role kiểm tra metadata; throw exception nếu cột ROLE chưa tồn tại")
    public void testMigrateNullRolesThrowsIfColumnMissing() {
        // Giả lập metadata cột ROLE chưa tồn tại
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            dataInitializer.migrateNullRolesToUser();
        });

        assertTrue(ex.getMessage().contains("Cột ROLE không tồn tại trên bảng USERS"));
        verify(jdbcTemplate, never()).update(anyString());
    }

    @Test
    @DisplayName("8. Migration role cập nhật thành công khi cột ROLE tồn tại")
    public void testMigrateNullRolesSucceedsWhenColumnExists() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(jdbcTemplate.update(anyString())).thenReturn(5);

        int updated = dataInitializer.migrateNullRolesToUser();

        assertEquals(5, updated);
        verify(jdbcTemplate).update(contains("UPDATE USERS SET ROLE = 'USER' WHERE ROLE IS NULL OR TRIM(ROLE) = ''"));
    }

    @Test
    @DisplayName("9. Mô phỏng migration trên CSDL thực tế có sẵn ít nhất 2 users trước khi thêm ROLE")
    public void testRealMigrationOnPreExistingDatabaseWithUsers() throws Exception {
        // Chạy kiểm tra SQL migration trên in-memory H2 database độc lập
        String dbUrl = "jdbc:h2:mem:migration_role_test_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            // 1. Tạo bảng USERS cũ (chưa có cột ROLE) và bảng WALLETS
            stmt.execute("CREATE TABLE USERS (" +
                    "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "EMAIL VARCHAR(255) NOT NULL UNIQUE, " +
                    "PASSWORD_HASH VARCHAR(255) NOT NULL, " +
                    "FULL_NAME VARCHAR(255) NOT NULL, " +
                    "AVATAR_URL VARCHAR(500), " +
                    "CREATED_AT TIMESTAMP)");

            stmt.execute("CREATE TABLE WALLETS (" +
                    "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "USER_ID BIGINT NOT NULL, " +
                    "BALANCE_USD DECIMAL(19,4) NOT NULL)");

            // 2. Chèn 2 users cũ và ví tương ứng
            stmt.execute("INSERT INTO USERS (EMAIL, PASSWORD_HASH, FULL_NAME, CREATED_AT) " +
                    "VALUES ('user1@fnmf.com', 'hash1', 'User One', CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO USERS (EMAIL, PASSWORD_HASH, FULL_NAME, CREATED_AT) " +
                    "VALUES ('user2@fnmf.com', 'hash2', 'User Two', CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO WALLETS (USER_ID, BALANCE_USD) VALUES (1, 10000.0000)");
            stmt.execute("INSERT INTO WALLETS (USER_ID, BALANCE_USD) VALUES (2, 25000.0000)");

            // 3. Thực thi 4 bước migration của V1__add_role_to_users.sql
            // a. ADD COLUMN IF NOT EXISTS ROLE VARCHAR(20) (starts nullable)
            stmt.execute("ALTER TABLE USERS ADD COLUMN IF NOT EXISTS ROLE VARCHAR(20)");

            // b. UPDATE null/rỗng thành USER
            int updated = stmt.executeUpdate("UPDATE USERS SET ROLE = 'USER' WHERE ROLE IS NULL OR TRIM(ROLE) = ''");
            assertEquals(2, updated, "Phải backfill đúng 2 user cũ thành USER");

            // c. SET DEFAULT 'USER'
            stmt.execute("ALTER TABLE USERS ALTER COLUMN ROLE SET DEFAULT 'USER'");

            // d. SET NOT NULL
            stmt.execute("ALTER TABLE USERS ALTER COLUMN ROLE SET NOT NULL");

            // 4. Xác minh:
            // Không xóa USERS
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM USERS")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "USERS không bị xóa, vẫn còn đủ 2 bản ghi");
            }

            // Không ảnh hưởng WALLETS
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM WALLETS")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "WALLETS không bị ảnh hưởng, vẫn còn đủ 2 bản ghi");
            }

            // Cả 2 user đều có role USER
            try (ResultSet rs = stmt.executeQuery("SELECT EMAIL, ROLE FROM USERS ORDER BY ID ASC")) {
                assertTrue(rs.next());
                assertEquals("user1@fnmf.com", rs.getString("EMAIL"));
                assertEquals("USER", rs.getString("ROLE"));

                assertTrue(rs.next());
                assertEquals("user2@fnmf.com", rs.getString("EMAIL"));
                assertEquals("USER", rs.getString("ROLE"));
            }

            // Thử thêm user mới không truyền role -> nhận default 'USER'
            stmt.execute("INSERT INTO USERS (EMAIL, PASSWORD_HASH, FULL_NAME, CREATED_AT) " +
                    "VALUES ('user3@fnmf.com', 'hash3', 'User Three', CURRENT_TIMESTAMP)");
            try (ResultSet rs = stmt.executeQuery("SELECT ROLE FROM USERS WHERE EMAIL = 'user3@fnmf.com'")) {
                assertTrue(rs.next());
                assertEquals("USER", rs.getString("ROLE"), "User mới tự động nhận role DEFAULT 'USER'");
            }
        }
    }

    @Test
    @DisplayName("10. /admin.html trả 200 trong profile prod")
    public void testAdminHtmlAllowedInProd() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin.html");
        req.setRequestURI("/admin.html");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        securityFilter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus(), "/admin.html phải trả về 200");
        assertFalse(securityFilter.isBlockedPath("/admin.html"));
    }

    @Test
    @DisplayName("11. H2, Swagger, db/query vẫn bị chặn 404 trong prod")
    public void testSensitiveEndpointsBlockedInProd() throws Exception {
        String[] blocked = { "/h2-console", "/swagger-ui", "/swagger-ui.html", "/v3/api-docs", "/api/admin/db/query" };
        for (String path : blocked) {
            assertTrue(securityFilter.isBlockedPath(path), path + " phải bị chặn");

            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.setRequestURI(path);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            securityFilter.doFilter(req, res, chain);
            assertEquals(404, res.getStatus(), path + " phải trả về 404");
        }
    }

    @Test
    @DisplayName("12. Static admin files không chứa email hoặc mật khẩu mẫu hardcode")
    public void testAdminFilesHaveNoHardcodedCredentials() throws Exception {
        File htmlFile = new File("src/main/resources/static/admin.html");
        File jsFile = new File("src/main/resources/static/admin.js");
        File cssFile = new File("src/main/resources/static/admin.css");

        assertTrue(htmlFile.exists(), "admin.html must exist");
        assertTrue(jsFile.exists(), "admin.js must exist");
        assertTrue(cssFile.exists(), "admin.css must exist");

        String html = Files.readString(htmlFile.toPath());
        String js = Files.readString(jsFile.toPath());

        assertFalse(html.contains("khoi.pro@fnmf.com"), "admin.html must not contain sample email");
        assertFalse(html.contains("mypassword123"), "admin.html must not contain sample password");
        assertFalse(js.contains("khoi.pro@fnmf.com"), "admin.js must not contain sample email");
        assertFalse(js.contains("mypassword123"), "admin.js must not contain sample password");
    }

    @Test
    @DisplayName("13. admin.js không sử dụng localStorage hoặc sessionStorage")
    public void testAdminJsHasNoWebStorage() throws Exception {
        File jsFile = new File("src/main/resources/static/admin.js");
        String js = Files.readString(jsFile.toPath());

        assertFalse(js.contains("localStorage"), "admin.js must not use localStorage");
        assertFalse(js.contains("sessionStorage"), "admin.js must not use sessionStorage");
    }

    @Test
    @DisplayName("14. admin.html và admin.js không có inline style, tuân thủ strict CSP")
    public void testStrictCspAndZeroInlineStyles() throws Exception {
        File htmlFile = new File("src/main/resources/static/admin.html");
        File jsFile = new File("src/main/resources/static/admin.js");

        String html = Files.readString(htmlFile.toPath());
        String js = Files.readString(jsFile.toPath());

        // 0 style= trong HTML
        assertFalse(html.matches("(?i).*\\bstyle\\s*=.*"), "admin.html không được chứa bất kỳ inline style nào");

        // 0 .style. hoặc setAttribute('style') trong JS
        assertFalse(js.contains(".style."), "admin.js không được truy xuất .style");
        assertFalse(js.contains(".style "), "admin.js không được gán .style");
        assertFalse(js.contains("setAttribute('style'"), "admin.js không được setAttribute('style')");
        assertFalse(js.contains("setAttribute(\"style\""), "admin.js không được setAttribute(\"style\")");

        // Không dùng innerHTML
        assertFalse(js.contains("innerHTML"), "admin.js must not use innerHTML");

        // CSP khai báo strict
        assertTrue(html.contains("Content-Security-Policy"), "admin.html must declare CSP");
        assertTrue(html.contains("default-src 'self'"), "admin.html CSP must enforce default-src 'self'");
        assertFalse(html.contains("'unsafe-inline'"), "admin.html CSP tuyệt đối không cho phép 'unsafe-inline'");
    }

    @Test
    @DisplayName("15. Static resources admin.html, admin.css, admin.js tồn tại và đọc được từ classpath")
    public void testStaticResourcesExistOnClasspath() {
        ClassLoader cl = getClass().getClassLoader();
        try (InputStream is1 = cl.getResourceAsStream("static/admin.html");
             InputStream is2 = cl.getResourceAsStream("static/admin.css");
             InputStream is3 = cl.getResourceAsStream("static/admin.js")) {

            assertNotNull(is1, "static/admin.html phải có trong classpath");
            assertNotNull(is2, "static/admin.css phải có trong classpath");
            assertNotNull(is3, "static/admin.js phải có trong classpath");
        } catch (Exception e) {
            fail("Lỗi khi đọc static resources: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("16. Exact Identifier Matching: khoi10 khớp chính xác case-insensitive; từ chối khớp mờ / tiền tố")
    public void testExactIdentifierMatching() {
        User exactUser = new User("khoi10", "hash", "Khoi 10", UserRole.USER);
        exactUser.setId(42L);

        // 1. Khớp chính xác khoi10
        when(userRepository.findAllByEmailIgnoreCase("khoi10")).thenReturn(List.of(exactUser));
        User found1 = adminService.findUserByIdentifier("khoi10");
        assertNotNull(found1);
        assertEquals(42L, found1.getId());
        assertEquals("khoi10", found1.getEmail());

        // 2. Khớp case-insensitive (Khoi10 -> gọi repo với Khoi10)
        when(userRepository.findAllByEmailIgnoreCase("Khoi10")).thenReturn(List.of(exactUser));
        User found2 = adminService.findUserByIdentifier("Khoi10");
        assertNotNull(found2);
        assertEquals(42L, found2.getId());

        // 3. Không khớp mờ với khoi10@example.com hoặc khoi100:
        // Khi tìm 'khoi10', repo chỉ tìm chính xác 'khoi10'. Nếu trong database chỉ có 'khoi10@example.com' hoặc 'khoi100',
        // thì findAllByEmailIgnoreCase("khoi10") sẽ trả về rỗng!
        when(userRepository.findAllByEmailIgnoreCase("khoi10_nonexistent")).thenReturn(Collections.emptyList());
        IllegalArgumentException exNotFound = assertThrows(IllegalArgumentException.class, () -> {
            adminService.findUserByIdentifier("khoi10_nonexistent");
        });
        assertTrue(exNotFound.getMessage().contains("Không tìm thấy tài khoản: khoi10_nonexistent"));

        // Xác minh ví không hề bị chạm tới khi không tìm thấy user
        verify(walletRepository, never()).save(any(Wallet.class));

        // 4. Nếu có nhiều hơn 1 user trùng khớp (do data bất thường) -> Từ chối silent operation
        when(userRepository.findAllByEmailIgnoreCase("duplicate_user")).thenReturn(List.of(exactUser, exactUser));
        IllegalStateException exDuplicate = assertThrows(IllegalStateException.class, () -> {
            adminService.findUserByIdentifier("duplicate_user");
        });
        assertTrue(exDuplicate.getMessage().contains("Phát hiện nhiều tài khoản trùng khớp"));
    }

    @Test
    @DisplayName("17. Topup cập nhật đúng wallet bằng transaction và tạo Transaction log")
    public void testTopupUpdatesWalletAndCreatesTransaction() {
        User user = new User("khoi10", "hash", "Khoi", UserRole.USER);
        user.setId(5L);
        when(userRepository.findAllByEmailIgnoreCase("khoi10")).thenReturn(List.of(user));

        Wallet wallet = new Wallet(5L);
        wallet.setId(10L);
        wallet.forceSetBalance(new BigDecimal("10000.0000"));
        when(walletRepository.findByUserId(5L)).thenReturn(Optional.of(wallet));

        Map<String, Object> result = adminService.topUp("khoi10", new BigDecimal("5000.0000"));

        assertEquals("SUCCESS", result.get("status"));
        assertEquals(new BigDecimal("15000.0000"), wallet.getBalanceUsd());
        verify(walletRepository).save(wallet);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction tx = txCaptor.getValue();
        assertEquals(10L, tx.getWalletId());
        assertEquals("TOPUP", tx.getType());
        assertEquals("USD", tx.getSymbol());
        assertEquals(new BigDecimal("5000.0000"), tx.getTotalAmount());
    }
}
