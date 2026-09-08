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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
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
    @DisplayName("6. Bootstrap enabled tạo hoặc promote admin")
    public void testBootstrapEnabledPromotesAdmin() {
        when(env.getProperty("FNMF_ADMIN_BOOTSTRAP_ENABLED")).thenReturn("true");
        when(env.getProperty("FNMF_ADMIN_EMAIL")).thenReturn("superadmin@fnmf.com");
        when(env.getProperty("FNMF_ADMIN_PASSWORD")).thenReturn("P@ssw0rdSecure!");
        when(passwordEncoder.encode("P@ssw0rdSecure!")).thenReturn("bcrypt_hash");

        User existingUser = new User("superadmin@fnmf.com", "old_hash", "Admin", UserRole.USER);
        when(userRepository.findByEmail("superadmin@fnmf.com")).thenReturn(Optional.of(existingUser));

        dataInitializer.bootstrapAdminIfNeeded();

        assertEquals(UserRole.ADMIN, existingUser.getRole());
        assertEquals("bcrypt_hash", existingUser.getPasswordHash());
        verify(userRepository).save(existingUser);
    }

    @Test
    @DisplayName("7. Migration user cũ có null role sang USER")
    public void testMigrateNullRolesToUser() {
        when(jdbcTemplate.update(anyString())).thenReturn(5);

        int updated = dataInitializer.migrateNullRolesToUser();

        assertEquals(5, updated);
        verify(jdbcTemplate).update(contains("UPDATE USERS SET ROLE = 'USER' WHERE ROLE IS NULL OR TRIM(ROLE) = ''"));
    }

    @Test
    @DisplayName("8. /admin.html trả 200 trong profile prod")
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
    @DisplayName("9. H2, Swagger, db/query vẫn bị chặn 404 trong prod")
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
    @DisplayName("10. Static admin files không chứa email hoặc mật khẩu mẫu hardcode")
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
    @DisplayName("11. admin.js không sử dụng localStorage hoặc sessionStorage")
    public void testAdminJsHasNoWebStorage() throws Exception {
        File jsFile = new File("src/main/resources/static/admin.js");
        String js = Files.readString(jsFile.toPath());

        assertFalse(js.contains("localStorage"), "admin.js must not use localStorage");
        assertFalse(js.contains("sessionStorage"), "admin.js must not use sessionStorage");
    }

    @Test
    @DisplayName("12. Dữ liệu HTML không dùng innerHTML và có CSP chặn script inline")
    public void testAntiXssAndCspPresent() throws Exception {
        File htmlFile = new File("src/main/resources/static/admin.html");
        File jsFile = new File("src/main/resources/static/admin.js");

        String html = Files.readString(htmlFile.toPath());
        String js = Files.readString(jsFile.toPath());

        assertFalse(js.contains("innerHTML"), "admin.js must not use innerHTML");
        assertTrue(html.contains("Content-Security-Policy"), "admin.html must declare CSP");
        assertTrue(html.contains("default-src 'self'"), "admin.html CSP must enforce default-src 'self'");
    }

    @Test
    @DisplayName("13. khoi10 có thể được tìm đúng qua identifier matches khi tồn tại")
    public void testFindUserByIdentifierMatches() {
        User target = new User("khoi10@fnmf.com", "hash", "Khoi 10", UserRole.USER);
        target.setId(42L);

        when(userRepository.findByIdentifierMatches("khoi10")).thenReturn(List.of(target));

        User found = adminService.findUserByIdentifier("khoi10");
        assertNotNull(found);
        assertEquals("khoi10@fnmf.com", found.getEmail());
        assertEquals(42L, found.getId());
    }

    @Test
    @DisplayName("14. Topup cập nhật đúng wallet và tạo Transaction record")
    public void testTopupUpdatesWalletAndCreatesTransaction() {
        User user = new User("khoi10", "hash", "Khoi", UserRole.USER);
        user.setId(5L);
        when(userRepository.findByIdentifierMatches("khoi10")).thenReturn(List.of(user));

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
