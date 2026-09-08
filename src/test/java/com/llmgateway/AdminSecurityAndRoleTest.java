package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.DataInitializer;
import com.llmgateway.controller.AdminController;
import com.llmgateway.dto.auth.AuthResponse;
import com.llmgateway.dto.auth.LoginRequest;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.entity.Holding;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private ObjectMapper objectMapper;

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
        objectMapper = new ObjectMapper();

        adminService = new AdminService(userRepository, walletRepository);
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
    @DisplayName("1. Đăng ký email hợp lệ thành công với role USER và ví $10,000")
    public void testRegister_validEmail_success() {
        RegisterRequest req = new RegisterRequest("newtrader@fnmf.com", "secret123", "New Trader");

        when(userRepository.findByEmailIgnoreCase("newtrader@fnmf.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed_secret");

        User savedUser = new User("newtrader@fnmf.com", "hashed_secret", "New Trader", UserRole.USER);
        savedUser.setId(10L);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        Wallet savedWallet = new Wallet(10L);
        savedWallet.setId(20L);
        when(walletRepository.save(any(Wallet.class))).thenReturn(savedWallet);
        when(jwtUtil.generateToken("newtrader@fnmf.com", 10L)).thenReturn("mock.jwt.token");

        AuthResponse resp = authService.register(req);

        assertNotNull(resp);
        assertEquals("USER", resp.getUser().getRole());
        assertEquals("newtrader@fnmf.com", resp.getUser().getEmail());
        assertTrue(resp.getUser().isValidEmail());
        assertFalse(resp.getUser().isNeedsEmailUpdate());
    }

    @Test
    @DisplayName("2. Đăng ký với email sai định dạng bị từ chối")
    public void testRegister_invalidEmail_rejected() {
        String[] invalidEmails = { "khoi10", "notanemail", "user@", "@domain.com", "user@domain", "user space@test.com" };
        for (String bad : invalidEmails) {
            RegisterRequest req = new RegisterRequest(bad, "secret123", "Bad Email User");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> authService.register(req));
            assertTrue(ex.getMessage().contains("Định dạng email không hợp lệ"));
        }
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("3. Email được normalize lowercase bằng Locale.ROOT khi đăng ký")
    public void testRegister_emailNormalizedToLowercase() {
        RegisterRequest req = new RegisterRequest("  Khoi.PRO@FNMF.COM  ", "secret123", "Khoi Pro");

        when(userRepository.findByEmailIgnoreCase("khoi.pro@fnmf.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed_secret");

        User savedUser = new User("khoi.pro@fnmf.com", "hashed_secret", "Khoi Pro", UserRole.USER);
        savedUser.setId(15L);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(walletRepository.save(any(Wallet.class))).thenReturn(new Wallet(15L));
        when(jwtUtil.generateToken("khoi.pro@fnmf.com", 15L)).thenReturn("jwt.token");

        authService.register(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("khoi.pro@fnmf.com", captor.getValue().getEmail());
    }

    @Test
    @DisplayName("4. Đăng ký email trùng lặp khác hoa/thường bị từ chối")
    public void testRegister_duplicateCaseInsensitive_rejected() {
        RegisterRequest req = new RegisterRequest("Trader@fnmf.com", "secret123");

        User existing = new User("trader@fnmf.com", "hash", "Trader", UserRole.USER);
        when(userRepository.findByEmailIgnoreCase("trader@fnmf.com")).thenReturn(Optional.of(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> authService.register(req));
        assertTrue(ex.getMessage().contains("đã tồn tại trên hệ thống"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("5. Đăng nhập bằng email case-insensitive thành công")
    public void testLogin_caseInsensitive_success() {
        LoginRequest req = new LoginRequest("TrAdEr@FnMf.CoM", "secret123");

        User existing = new User("trader@fnmf.com", "bcrypt_hash", "Trader", UserRole.USER);
        existing.setId(7L);
        when(userRepository.findByEmailIgnoreCase("trader@fnmf.com")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("secret123", "bcrypt_hash")).thenReturn(true);
        when(walletRepository.findByUserId(7L)).thenReturn(Optional.of(new Wallet(7L)));
        when(jwtUtil.generateToken("trader@fnmf.com", 7L)).thenReturn("jwt.token");

        AuthResponse resp = authService.login(req);

        assertNotNull(resp);
        assertEquals("trader@fnmf.com", resp.getUser().getEmail());
        assertEquals("Đăng nhập thành công!", resp.getMessage());
    }

    @Test
    @DisplayName("6. @JsonAlias('username') cũ deserialize vào email, và serialize chỉ chứa 'email' tuyệt đối không chứa 'username'")
    public void testJsonAliasUsernameWithEmail_deserializesAndWorks() throws Exception {
        // Deserialization từ legacy client dùng field "username"
        String jsonPayload = "{\"username\":\"legacy.app@fnmf.com\",\"password\":\"secret123\"}";
        LoginRequest loginReq = objectMapper.readValue(jsonPayload, LoginRequest.class);

        assertEquals("legacy.app@fnmf.com", loginReq.getEmail());
        assertEquals("secret123", loginReq.getPassword());

        String regPayload = "{\"username\":\"legacy.reg@fnmf.com\",\"password\":\"secret123\",\"fullName\":\"Legacy User\"}";
        RegisterRequest regReq = objectMapper.readValue(regPayload, RegisterRequest.class);

        assertEquals("legacy.reg@fnmf.com", regReq.getEmail());
        assertEquals("Legacy User", regReq.getFullName());

        // Serialization phải chỉ chứa "email", tuyệt đối không chứa "username"
        String serializedLogin = objectMapper.writeValueAsString(loginReq);
        assertTrue(serializedLogin.contains("\"email\":\"legacy.app@fnmf.com\""));
        assertFalse(serializedLogin.contains("\"username\""), "JSON serialize của LoginRequest không được chứa field 'username'");

        String serializedReg = objectMapper.writeValueAsString(regReq);
        assertTrue(serializedReg.contains("\"email\":\"legacy.reg@fnmf.com\""));
        assertFalse(serializedReg.contains("\"username\""), "JSON serialize của RegisterRequest không được chứa field 'username'");
    }

    @Test
    @DisplayName("7. Legacy khoi10 không bị tự động đổi/xóa khi hiển thị overview và được đánh dấu needsEmailUpdate")
    public void testLegacyUserPreservedWithoutModification() {
        String token = "admin.valid.jwt";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        Map<String, Object> legacyRow = new java.util.HashMap<>();
        legacyRow.put("id", 100L);
        legacyRow.put("email", "khoi10");
        legacyRow.put("full_name", "Khoi 10");
        legacyRow.put("role", "USER");

        when(jdbcTemplate.queryForList(contains("SELECT id, email, full_name"))).thenReturn(List.of(legacyRow));

        ResponseEntity<Map<String, Object>> response = adminController.getDatabaseOverview("Bearer " + token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> users = (List<Map<String, Object>>) response.getBody().get("users");
        assertEquals(1, users.size());
        Map<String, Object> u = users.get(0);
        assertEquals("khoi10", u.get("email"));
        assertEquals(true, u.get("needsEmailUpdate"));
        assertEquals(false, u.get("validEmail"));
    }

    @Test
    @DisplayName("8. ADMIN đổi khoi10 sang email thật, giữ nguyên user.id")
    public void testAdminUpdatesLegacyUserEmail_keepsUserId() {
        User legacyUser = new User("khoi10", "hash", "Khoi 10", UserRole.USER);
        legacyUser.setId(42L);

        when(userRepository.findById(42L)).thenReturn(Optional.of(legacyUser));
        when(userRepository.findByEmailIgnoreCase("khoi.real@fnmf.com")).thenReturn(Optional.empty());

        Map<String, Object> res = adminService.updateUserEmail("admin@fnmf.com", 42L, "Khoi.Real@fnmf.com");

        assertEquals("SUCCESS", res.get("status"));
        assertEquals(42L, res.get("userId"));
        assertEquals("khoi10", res.get("oldEmail"));
        assertEquals("khoi.real@fnmf.com", res.get("newEmail"));
        assertEquals("khoi.real@fnmf.com", legacyUser.getEmail());
        assertEquals(42L, legacyUser.getId());
        verify(userRepository).save(legacyUser);
    }

    @Test
    @DisplayName("9. Wallet, holdings, transactions vẫn gắn đúng user sau khi đổi email")
    public void testWalletAndHoldingsPreservedAfterEmailUpdate() {
        User legacyUser = new User("khoi10", "hash", "Khoi", UserRole.USER);
        legacyUser.setId(5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(legacyUser));
        when(userRepository.findByEmailIgnoreCase("khoi.new@fnmf.com")).thenReturn(Optional.empty());

        Wallet wallet = new Wallet(5L);
        wallet.setId(10L);
        wallet.forceSetBalance(new BigDecimal("25000.0000"));
        when(walletRepository.findByUserId(5L)).thenReturn(Optional.of(wallet));

        adminService.updateUserEmail("admin@fnmf.com", 5L, "khoi.new@fnmf.com");

        // Ví không bị xóa hoặc thay đổi quan hệ
        verify(walletRepository, never()).delete(any());
        verify(holdingRepository, never()).deleteByWalletId(anyLong());
        verify(transactionRepository, never()).deleteByWalletId(anyLong());
        assertEquals(5L, wallet.getUserId());
        assertEquals(new BigDecimal("25000.0000"), wallet.getBalanceUsd());
    }

    @Test
    @DisplayName("10. Đổi sang email đã tồn tại bị rollback / rejected")
    public void testAdminUpdateEmail_duplicateRejected() {
        User target = new User("khoi10", "hash", "Khoi 10", UserRole.USER);
        target.setId(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));

        User other = new User("existing@fnmf.com", "hash", "Existing", UserRole.USER);
        other.setId(99L);
        when(userRepository.findByEmailIgnoreCase("existing@fnmf.com")).thenReturn(Optional.of(other));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            adminService.updateUserEmail("admin@fnmf.com", 42L, "existing@fnmf.com");
        });

        assertTrue(ex.getMessage().contains("đã được sử dụng bởi tài khoản khác"));
        assertEquals("khoi10", target.getEmail(), "Email của user không được thay đổi");
        verify(userRepository, never()).save(target);
    }

    @Test
    @DisplayName("11. User thường không đổi được email người khác (403)")
    public void testNormalUserCannotUpdateEmail_forbidden() {
        String token = "user.token.jwt";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("user@fnmf.com");

        User user = new User("user@fnmf.com", "hash", "User", UserRole.USER);
        when(userRepository.findByEmail("user@fnmf.com")).thenReturn(Optional.of(user));

        ResponseEntity<Map<String, Object>> response = adminController.updateUserEmail(
                "Bearer " + token, 42L, Map.of("email", "hacked@fnmf.com")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("12a. ADMIN set balance bằng email chính xác: 200 & DB thay đổi đúng")
    public void testAdminSetBalance_exactEmail_success() {
        String token = "admin.valid.token";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        User targetUser = new User("trader@fnmf.com", "hash", "Trader", UserRole.USER);
        targetUser.setId(8L);
        when(userRepository.findAllByEmailIgnoreCase("trader@fnmf.com")).thenReturn(List.of(targetUser));

        Wallet wallet = new Wallet(8L);
        wallet.setId(18L);
        wallet.forceSetBalance(new BigDecimal("10000.0000"));
        when(walletRepository.findByUserId(8L)).thenReturn(Optional.of(wallet));

        Map<String, Object> body = Map.of("email", "trader@fnmf.com", "balance", 50000.00);
        ResponseEntity<Map<String, Object>> response = adminController.setBalance("Bearer " + token, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals("trader@fnmf.com", response.getBody().get("email"));
        assertEquals(new BigDecimal("50000.0"), wallet.getBalanceUsd());
        verify(walletRepository).save(wallet);
    }

    @Test
    @DisplayName("12b. USER thường gọi set-balance bị từ chối với HTTP 403")
    public void testNormalUserSetBalance_forbidden403() {
        String token = "user.token.jwt";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("user@fnmf.com");

        User normalUser = new User("user@fnmf.com", "hash", "User", UserRole.USER);
        when(userRepository.findByEmail("user@fnmf.com")).thenReturn(Optional.of(normalUser));

        ResponseEntity<Map<String, Object>> response = adminController.setBalance(
                "Bearer " + token, Map.of("email", "target@fnmf.com", "balance", 50000.00)
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("12c. Không có token hoặc token không hợp lệ gọi set-balance trả về HTTP 401")
    public void testNoTokenSetBalance_unauthorized401() {
        ResponseEntity<Map<String, Object>> res1 = adminController.setBalance(
                null, Map.of("email", "target@fnmf.com", "balance", 50000.00)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, res1.getStatusCode());

        when(jwtUtil.validateToken("bad.token")).thenReturn(false);
        ResponseEntity<Map<String, Object>> res2 = adminController.setBalance(
                "Bearer bad.token", Map.of("email", "target@fnmf.com", "balance", 50000.00)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, res2.getStatusCode());
    }

    @Test
    @DisplayName("12d. Set balance với email không tồn tại trả về HTTP 400")
    public void testSetBalance_userNotFound_badRequest400() {
        String token = "admin.valid.token";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        when(userRepository.findAllByEmailIgnoreCase("nonexistent@fnmf.com")).thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = adminController.setBalance(
                "Bearer " + token, Map.of("email", "nonexistent@fnmf.com", "balance", 50000.00)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("message").toString().contains("Không tìm thấy tài khoản"));
    }

    @Test
    @DisplayName("12e. Set balance khi người dùng chưa có ví tiền trả về HTTP 400 với thông báo rõ ràng")
    public void testSetBalance_userHasNoWallet_badRequest400() {
        String token = "admin.valid.token";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        User userNoWallet = new User("nowallet@fnmf.com", "hash", "No Wallet", UserRole.USER);
        userNoWallet.setId(99L);
        when(userRepository.findAllByEmailIgnoreCase("nowallet@fnmf.com")).thenReturn(List.of(userNoWallet));
        when(walletRepository.findByUserId(99L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = adminController.setBalance(
                "Bearer " + token, Map.of("email", "nowallet@fnmf.com", "balance", 50000.00)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Người dùng chưa có ví tiền", response.getBody().get("message"));
    }

    @Test
    @DisplayName("12f. Set balance với số dư âm, NaN, Infinity, thiếu hoặc rỗng trả về HTTP 400")
    public void testSetBalance_invalidBalanceValues_badRequest400() {
        String token = "admin.valid.token";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        Object[] invalidBalances = {
                -100, -0.01, "NaN", "Infinity", "-Infinity", "+Infinity",
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                "", "   ", "abc", null
        };

        for (Object invalidBal : invalidBalances) {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("email", "trader@fnmf.com");
            if (invalidBal != null) {
                body.put("balance", invalidBal);
            }

            ResponseEntity<Map<String, Object>> response = adminController.setBalance("Bearer " + token, body);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "Phải trả về 400 với số dư: " + invalidBal);
            assertNotNull(response.getBody());
            assertEquals("ERROR", response.getBody().get("status"));
        }
    }

    @Test
    @DisplayName("12g. Các endpoint topup, grant-crypto, reset đã bị xóa hoàn toàn và trả về 404")
    public void testRemovedEndpoints_return404AndNoMethodsExist() throws Exception {
        // 1. Kiểm tra reflection: không còn method topUp, grantCrypto, resetAccount trong Controller và Service
        assertThrows(NoSuchMethodException.class, () -> AdminController.class.getDeclaredMethod("topUp", String.class, Map.class));
        assertThrows(NoSuchMethodException.class, () -> AdminController.class.getDeclaredMethod("grantCrypto", String.class, Map.class));
        assertThrows(NoSuchMethodException.class, () -> AdminController.class.getDeclaredMethod("resetAccount", String.class, Map.class));

        assertThrows(NoSuchMethodException.class, () -> AdminService.class.getDeclaredMethod("topUp", String.class, BigDecimal.class));
        assertThrows(NoSuchMethodException.class, () -> AdminService.class.getDeclaredMethod("grantCrypto", String.class, String.class, BigDecimal.class, BigDecimal.class));
        assertThrows(NoSuchMethodException.class, () -> AdminService.class.getDeclaredMethod("resetAccount", String.class));

        // 2. Kiểm tra MockMvc: các endpoint trả về 404 NOT_FOUND
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(adminController).build();
        mockMvc.perform(post("/api/admin/topup").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/grant-crypto").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/reset").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("13. Exact Identifier Matching: không khớp prefix hay LIKE")
    public void testExactIdentifierMatching_noPrefixFuzzy() {
        when(userRepository.findAllByEmailIgnoreCase("khoi")).thenReturn(Collections.emptyList());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            adminService.findUserByEmailOrId("khoi");
        });
        assertTrue(ex.getMessage().contains("Không tìm thấy tài khoản: khoi"));
    }

    @Test
    @DisplayName("14. Flyway V2 chạy an toàn trên CSDL có sẵn, chuẩn hoá lowercase và không xoá user")
    public void testFlywayV2_preservesUsersAndCreatesIndex() throws Exception {
        String dbUrl = "jdbc:h2:mem:v2_migration_test_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             Statement stmt = conn.createStatement()) {

            // 1. Tạo bảng USERS và WALLETS
            stmt.execute("CREATE TABLE USERS (" +
                    "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "EMAIL VARCHAR(255) NOT NULL, " +
                    "PASSWORD_HASH VARCHAR(255) NOT NULL, " +
                    "FULL_NAME VARCHAR(255) NOT NULL, " +
                    "ROLE VARCHAR(20) DEFAULT 'USER' NOT NULL, " +
                    "CREATED_AT TIMESTAMP)");

            stmt.execute("CREATE TABLE WALLETS (" +
                    "ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "USER_ID BIGINT NOT NULL, " +
                    "BALANCE_USD DECIMAL(19,4) NOT NULL)");

            // 2. Chèn 1 email hoa, 1 email thường và 1 legacy identifier 'khoi10'
            stmt.execute("INSERT INTO USERS (EMAIL, PASSWORD_HASH, FULL_NAME, CREATED_AT) " +
                    "VALUES ('TRADER@FNMF.COM', 'hash1', 'Trader One', CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO USERS (EMAIL, PASSWORD_HASH, FULL_NAME, CREATED_AT) " +
                    "VALUES ('user2@fnmf.com', 'hash2', 'User Two', CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO USERS (EMAIL, PASSWORD_HASH, FULL_NAME, CREATED_AT) " +
                    "VALUES ('khoi10', 'hash3', 'Khoi Legacy', CURRENT_TIMESTAMP)");

            stmt.execute("INSERT INTO WALLETS (USER_ID, BALANCE_USD) VALUES (1, 10000.0000)");
            stmt.execute("INSERT INTO WALLETS (USER_ID, BALANCE_USD) VALUES (2, 20000.0000)");
            stmt.execute("INSERT INTO WALLETS (USER_ID, BALANCE_USD) VALUES (3, 30000.0000)");

            // 3. Thực thi bước chuẩn hoá V2: UPDATE USERS SET EMAIL = LOWER(TRIM(EMAIL))
            stmt.execute("UPDATE USERS SET EMAIL = LOWER(TRIM(EMAIL)) WHERE EMAIL IS NOT NULL AND EMAIL <> LOWER(TRIM(EMAIL))");

            // 4. Tạo unique index trên bảng (trên H2 dùng cú pháp tương thích, trên Postgres V2 dùng LOWER(EMAIL))
            boolean isH2 = conn.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2");
            if (isH2) {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON USERS (EMAIL)");
            } else {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower ON USERS (LOWER(EMAIL))");
            }

            // Đồng thời xác minh file migration V2__normalize_and_enforce_user_email.sql chứa chuẩn PostgreSQL
            File v2File = new File("src/main/resources/db/migration/V2__normalize_and_enforce_user_email.sql");
            assertTrue(v2File.exists(), "V2 migration file must exist");
            String v2Sql = Files.readString(v2File.toPath());
            assertTrue(v2Sql.contains("idx_users_email_lower ON USERS (LOWER(EMAIL))"));
            assertTrue(v2Sql.contains("UPDATE USERS"));

            // 5. Kiểm tra toàn bộ 3 bản ghi vẫn tồn tại, TRADER@FNMF.COM thành trader@fnmf.com, khoi10 giữ nguyên
            try (ResultSet rs = stmt.executeQuery("SELECT ID, EMAIL FROM USERS ORDER BY ID ASC")) {
                assertTrue(rs.next());
                assertEquals("trader@fnmf.com", rs.getString("EMAIL"));

                assertTrue(rs.next());
                assertEquals("user2@fnmf.com", rs.getString("EMAIL"));

                assertTrue(rs.next());
                assertEquals("khoi10", rs.getString("EMAIL"), "Legacy khoi10 phải giữ nguyên vẹn");
            }

            // Ví vẫn nguyên vẹn
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM WALLETS")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("15. Bootstrap admin từ chối email không hợp lệ (như khoi10)")
    public void testBootstrapAdmin_invalidEmail_rejected() {
        when(env.getProperty("FNMF_ADMIN_BOOTSTRAP_ENABLED")).thenReturn("true");
        when(env.getProperty("FNMF_ADMIN_EMAIL")).thenReturn("khoi10"); // Invalid format
        when(env.getProperty("FNMF_ADMIN_PASSWORD")).thenReturn("test-mock-admin-password-fixture");

        dataInitializer.bootstrapAdminIfNeeded();

        // Không được phép lưu user admin với identifier khoi10
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("16. Bootstrap admin hợp lệ với fixture password thành công")
    public void testBootstrapAdmin_validEmail_promotesAdmin() {
        String fixturePassword = "  test-mock-admin-password-fixture  ";
        when(env.getProperty("FNMF_ADMIN_BOOTSTRAP_ENABLED")).thenReturn("true");
        when(env.getProperty("FNMF_ADMIN_EMAIL")).thenReturn("superadmin@fnmf.com");
        when(env.getProperty("FNMF_ADMIN_PASSWORD")).thenReturn(fixturePassword);
        when(passwordEncoder.encode(fixturePassword)).thenReturn("bcrypt_hash");

        User existingUser = new User("superadmin@fnmf.com", "old_hash", "Admin", UserRole.USER);
        when(userRepository.findByEmailIgnoreCase("superadmin@fnmf.com")).thenReturn(Optional.of(existingUser));

        dataInitializer.bootstrapAdminIfNeeded();

        assertEquals(UserRole.ADMIN, existingUser.getRole());
        assertEquals("bcrypt_hash", existingUser.getPasswordHash());
        verify(passwordEncoder).encode(eq(fixturePassword));
        verify(userRepository).save(existingUser);
    }

    @Test
    @DisplayName("17. JWT sai hoặc hết hạn bị từ chối với HTTP 401")
    public void testInvalidOrExpiredJwtRejectedWith401() {
        ResponseEntity<Map<String, Object>> res1 = adminController.getDatabaseOverview(null);
        assertEquals(HttpStatus.UNAUTHORIZED, res1.getStatusCode());

        when(jwtUtil.validateToken("bad.token")).thenReturn(false);
        ResponseEntity<Map<String, Object>> res2 = adminController.getDatabaseOverview("Bearer bad.token");
        assertEquals(HttpStatus.UNAUTHORIZED, res2.getStatusCode());
    }

    @Test
    @DisplayName("18. /admin.html trả 200 trong profile prod")
    public void testAdminHtmlAllowedInProd() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin.html");
        req.setRequestURI("/admin.html");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        securityFilter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertFalse(securityFilter.isBlockedPath("/admin.html"));
    }

    @Test
    @DisplayName("19. H2, Swagger, db/query vẫn bị chặn 404 trong prod")
    public void testSensitiveEndpointsBlockedInProd() throws Exception {
        String[] blocked = { "/h2-console", "/swagger-ui", "/swagger-ui.html", "/v3/api-docs", "/api/admin/db/query" };
        for (String path : blocked) {
            assertTrue(securityFilter.isBlockedPath(path));

            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.setRequestURI(path);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            securityFilter.doFilter(req, res, chain);
            assertEquals(404, res.getStatus());
        }
    }

    @Test
    @DisplayName("20. Static admin files không chứa email hoặc mật khẩu mẫu hardcode")
    public void testAdminFilesHaveNoHardcodedCredentials() throws Exception {
        File htmlFile = new File("src/main/resources/static/admin.html");
        File jsFile = new File("src/main/resources/static/admin.js");
        File cssFile = new File("src/main/resources/static/admin.css");

        assertTrue(htmlFile.exists());
        assertTrue(jsFile.exists());
        assertTrue(cssFile.exists());

        String html = Files.readString(htmlFile.toPath());
        String js = Files.readString(jsFile.toPath());

        assertFalse(html.contains("khoi.pro@fnmf.com"));
        assertFalse(html.contains("mypassword123"));
        assertFalse(js.contains("khoi.pro@fnmf.com"));
        assertFalse(js.contains("mypassword123"));
    }

    @Test
    @DisplayName("21. admin.js không sử dụng localStorage hoặc sessionStorage")
    public void testAdminJsHasNoWebStorage() throws Exception {
        File jsFile = new File("src/main/resources/static/admin.js");
        String js = Files.readString(jsFile.toPath());

        assertFalse(js.contains("localStorage"));
        assertFalse(js.contains("sessionStorage"));
    }

    @Test
    @DisplayName("22. admin.html và admin.js không có inline style, tuân thủ strict CSP")
    public void testStrictCspAndZeroInlineStyles() throws Exception {
        File htmlFile = new File("src/main/resources/static/admin.html");
        File jsFile = new File("src/main/resources/static/admin.js");

        String html = Files.readString(htmlFile.toPath());
        String js = Files.readString(jsFile.toPath());

        // 0 style= trong HTML
        assertFalse(html.matches("(?i).*\\bstyle\\s*=.*"));

        // 0 .style. hoặc setAttribute('style') trong JS
        assertFalse(js.contains(".style."));
        assertFalse(js.contains(".style "));
        assertFalse(js.contains("setAttribute('style'"));
        assertFalse(js.contains("setAttribute(\"style\""));

        // Không dùng innerHTML
        assertFalse(js.contains("innerHTML"));

        // CSP khai báo strict
        assertTrue(html.contains("Content-Security-Policy"));
        assertTrue(html.contains("default-src 'self'"));
        assertFalse(html.contains("'unsafe-inline'"));
    }

    @Test
    @DisplayName("23. Static resources admin.html, admin.css, admin.js tồn tại và đọc được từ classpath")
    public void testStaticResourcesExistOnClasspath() {
        ClassLoader cl = getClass().getClassLoader();
        try (InputStream is1 = cl.getResourceAsStream("static/admin.html");
             InputStream is2 = cl.getResourceAsStream("static/admin.css");
             InputStream is3 = cl.getResourceAsStream("static/admin.js")) {

            assertNotNull(is1);
            assertNotNull(is2);
            assertNotNull(is3);
        } catch (Exception e) {
            fail("Lỗi khi đọc static resources: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("24. UI admin.html và admin.js không còn ID/form/handler đã xóa và có modal đặt số dư mới")
    public void testAdminUI_hasNoRemovedElementsOrHandlers() throws Exception {
        File htmlFile = new File("src/main/resources/static/admin.html");
        File jsFile = new File("src/main/resources/static/admin.js");

        assertTrue(htmlFile.exists());
        assertTrue(jsFile.exists());

        String html = Files.readString(htmlFile.toPath());
        String js = Files.readString(jsFile.toPath());

        // Các ID và form cũ đã bị xóa hoàn toàn khỏi HTML và JS
        String[] removedIds = {
                "btnDoTopup", "btnDoGrant", "btnDoReset",
                "topupTarget", "topupAmount",
                "cryptoTarget", "cryptoSymbol", "cryptoQty", "cryptoPrice",
                "manageTarget", "customBalance", "btnDoSetBalance",
                "actionsGrid"
        };
        for (String id : removedIds) {
            assertFalse(html.contains("id=\"" + id + "\""), "admin.html không được chứa id: " + id);
            assertFalse(js.contains("document.getElementById('" + id + "')"), "admin.js không được truy vấn id: " + id);
            assertFalse(js.contains("document.getElementById(\"" + id + "\")"), "admin.js không được truy vấn id: " + id);
        }

        // Không còn endpoint cũ trong JS
        assertFalse(js.contains("/api/admin/topup"));
        assertFalse(js.contains("/api/admin/grant-crypto"));
        assertFalse(js.contains("/api/admin/reset"));

        // Modal Đặt số dư mới có trong HTML
        assertTrue(html.contains("id=\"balanceUpdateModal\""));
        assertTrue(html.contains("id=\"modalBalanceUserId\""));
        assertTrue(html.contains("id=\"modalBalanceEmail\""));
        assertTrue(html.contains("id=\"modalBalanceCurrent\""));
        assertTrue(html.contains("id=\"modalNewBalanceInput\""));
        assertTrue(html.contains("id=\"btnCancelBalanceModal\""));
        assertTrue(html.contains("id=\"btnConfirmBalanceModal\""));

        // Handler đặt số dư có trong JS
        assertTrue(js.contains("openBalanceModal"));
        assertTrue(js.contains("closeBalanceModal"));
        assertTrue(js.contains("confirmBalanceUpdate"));
        assertTrue(js.contains("/api/admin/set-balance"));
    }

    @Test
    @DisplayName("25. Overview chỉ truy vấn USERS và WALLETS, không chứa holdings/transactions, ADMIN nhận 200")
    public void testOverview_adminValid_returns200_onlyUsersAndWallets() {
        String token = "admin.overview.token";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        Map<String, Object> userRow = Map.of("id", 1L, "email", "trader@fnmf.com", "role", "USER");
        Map<String, Object> walletRow = Map.of("id", 10L, "user_id", 1L, "balance_usd", new BigDecimal("10000.0000"));

        when(jdbcTemplate.queryForList(contains("FROM USERS"))).thenReturn(List.of(userRow));
        when(jdbcTemplate.queryForList(contains("FROM WALLETS"))).thenReturn(List.of(walletRow));

        ResponseEntity<Map<String, Object>> response = adminController.getDatabaseOverview("Bearer " + token);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        // Phải có users và wallets
        assertTrue(response.getBody().containsKey("users"));
        assertTrue(response.getBody().containsKey("wallets"));

        // Tuyệt đối không chứa holdings hoặc transactions
        assertFalse(response.getBody().containsKey("holdings"), "Response không được chứa key 'holdings'");
        assertFalse(response.getBody().containsKey("transactions"), "Response không được chứa key 'transactions'");

        // Xác minh chỉ gọi query 2 bảng USERS và WALLETS
        verify(jdbcTemplate, times(1)).queryForList(contains("FROM USERS"));
        verify(jdbcTemplate, times(1)).queryForList(contains("FROM WALLETS"));
        verify(jdbcTemplate, never()).queryForList(contains("FROM HOLDINGS"));
        verify(jdbcTemplate, never()).queryForList(contains("FROM TRANSACTIONS"));
    }

    @Test
    @DisplayName("26. Overview: thiếu token nhận 401, USER thường nhận 403")
    public void testOverview_securityAuth_missingToken401_normalUser403() {
        // 1. Thiếu token -> 401
        ResponseEntity<Map<String, Object>> resNoToken = adminController.getDatabaseOverview(null);
        assertEquals(HttpStatus.UNAUTHORIZED, resNoToken.getStatusCode());

        // 2. Token không hợp lệ -> 401
        when(jwtUtil.validateToken("invalid.token")).thenReturn(false);
        ResponseEntity<Map<String, Object>> resInvalid = adminController.getDatabaseOverview("Bearer invalid.token");
        assertEquals(HttpStatus.UNAUTHORIZED, resInvalid.getStatusCode());

        // 3. User thường (ROLE = USER) -> 403
        String userToken = "user.normal.token";
        when(jwtUtil.validateToken(userToken)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(userToken)).thenReturn("trader@fnmf.com");

        User normalUser = new User("trader@fnmf.com", "hash", "Trader", UserRole.USER);
        when(userRepository.findByEmail("trader@fnmf.com")).thenReturn(Optional.of(normalUser));

        ResponseEntity<Map<String, Object>> resForbidden = adminController.getDatabaseOverview("Bearer " + userToken);
        assertEquals(HttpStatus.FORBIDDEN, resForbidden.getStatusCode());
    }

    @Test
    @DisplayName("27. Lỗi JDBC trong overview nhận HTTP 500 với message an toàn, không lộ SQL hay table")
    public void testOverview_jdbcError_returns500_safeMessage() {
        String token = "admin.error.token";
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getEmailFromToken(token)).thenReturn("admin@fnmf.com");

        User admin = new User("admin@fnmf.com", "hash", "Admin", UserRole.ADMIN);
        when(userRepository.findByEmail("admin@fnmf.com")).thenReturn(Optional.of(admin));

        // Giả lập lỗi truy vấn database (ví dụ: column does not exist)
        when(jdbcTemplate.queryForList(contains("FROM USERS")))
                .thenThrow(new org.springframework.dao.DataRetrievalFailureException("column created_at does not exist in table HOLDINGS"));

        ResponseEntity<Map<String, Object>> response = adminController.getDatabaseOverview("Bearer " + token);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ERROR", response.getBody().get("status"));
        assertEquals("Không thể tải dữ liệu quản trị", response.getBody().get("message"));

        // Không gửi raw SQL exception hoặc thông tin database ra client
        String respJson = response.getBody().toString();
        assertFalse(respJson.contains("HOLDINGS"));
        assertFalse(respJson.contains("created_at"));
        assertFalse(respJson.contains("DataRetrievalFailureException"));
    }

    @Test
    @DisplayName("28. Quét source AdminController xác nhận overview không còn chuỗi SELECT HOLDINGS hoặc TRANSACTIONS")
    public void testOverview_sourceHasNoHoldingsOrTransactionsSelect() throws Exception {
        File controllerFile = new File("src/main/java/com/llmgateway/controller/AdminController.java");
        assertTrue(controllerFile.exists());
        String code = Files.readString(controllerFile.toPath());

        // Tìm phương thức getDatabaseOverview
        int idx = code.indexOf("getDatabaseOverview");
        assertTrue(idx > 0, "Phải tìm thấy phương thức getDatabaseOverview trong AdminController");

        String overviewSection = code.substring(idx);
        assertFalse(overviewSection.contains("FROM HOLDINGS"), "getDatabaseOverview không được chứa truy vấn HOLDINGS");
        assertFalse(overviewSection.contains("FROM TRANSACTIONS"), "getDatabaseOverview không được chứa truy vấn TRANSACTIONS");
    }

    @Test
    @DisplayName("29. admin.js: login không báo thành công khi overview lỗi và loadDbOverview throw lỗi")
    public void testAdminJs_loginDoesNotReportSuccessWhenOverviewFails() throws Exception {
        File jsFile = new File("src/main/resources/static/admin.js");
        assertTrue(jsFile.exists());
        String js = Files.readString(jsFile.toPath());

        // Kiểm tra loadDbOverview ném lỗi khi response không OK
        assertTrue(js.contains("throw e;"), "loadDbOverview phải re-throw exception khi lỗi");

        // Kiểm tra thông báo an toàn khi overview thất bại trong login
        assertTrue(js.contains("Đăng nhập thành công nhưng không tải được dữ liệu quản trị"));

        // Kiểm tra login() bắt lỗi overview và return không chạy đoạn thông báo thành công
        int catchIdx = js.indexOf("catch (overviewErr)");
        int successIdx = js.indexOf("✅ Xác thực ADMIN hoàn tất");
        assertTrue(catchIdx > 0 && successIdx > 0);
        assertTrue(catchIdx < successIdx, "catch(overviewErr) phải nằm trước thông báo 'Xác thực ADMIN hoàn tất'");
    }
}
