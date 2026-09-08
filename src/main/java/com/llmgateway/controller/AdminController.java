package com.llmgateway.controller;

import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.service.AdminService;
import com.llmgateway.service.AiNewsService;
import com.llmgateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * SECURE FNMF CLOUD ADMIN CONTROL CENTER
 * =====================================================================
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;
    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final AiNewsService aiNewsService;

    @Autowired
    private Environment env;

    public AdminController(AdminService adminService,
                           JdbcTemplate jdbcTemplate,
                           JwtUtil jwtUtil,
                           UserRepository userRepository,
                           AiNewsService aiNewsService) {
        this.adminService = adminService;
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.aiNewsService = aiNewsService;
    }

    private static class AdminAuthResult {
        final HttpStatus status;
        final User adminUser;
        final String message;

        AdminAuthResult(HttpStatus status, User adminUser, String message) {
            this.status = status;
            this.adminUser = adminUser;
            this.message = message;
        }

        boolean isAuthorized() {
            return status == HttpStatus.OK && adminUser != null;
        }

        ResponseEntity<Map<String, Object>> toErrorResponse() {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", message);
            return ResponseEntity.status(status).body(err);
        }
    }

    /**
     * Xác thực Token và Role ADMIN:
     * - Thiếu token / sai format / token không hợp lệ / hết hạn -> 401 Unauthorized
     * - User không tồn tại -> 401 Unauthorized
     * - User tồn tại nhưng role != ADMIN -> 403 Forbidden
     * - User có role == ADMIN -> 200 OK
     */
    private AdminAuthResult verifyAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "Authorization token is missing or malformed");
        }
        String token = authHeader.substring(7).trim();
        if (token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.isEmpty() || !jwtUtil.validateToken(token)) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "Invalid or expired JWT token");
        }
        String email = jwtUtil.getEmailFromToken(token);
        if (email == null || email.isBlank()) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "Invalid token claims");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return new AdminAuthResult(HttpStatus.UNAUTHORIZED, null, "User associated with token not found");
        }

        if (user.getRole() != UserRole.ADMIN) {
            return new AdminAuthResult(HttpStatus.FORBIDDEN, user, "Access Denied: You do not have ADMIN privileges");
        }

        return new AdminAuthResult(HttpStatus.OK, user, null);
    }

    private String extractTargetIdentifier(Map<String, Object> body) {
        if (body == null) return null;
        Object val = body.containsKey("identifier") ? body.get("identifier") : body.get("email");
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    @PostMapping("/topup")
    public ResponseEntity<Map<String, Object>> topUp(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                     @RequestBody Map<String, Object> body) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String identifier = extractTargetIdentifier(body);
        if (identifier == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Tên đăng nhập hoặc email không được để trống"));
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(body.getOrDefault("amount", 10000).toString().trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số tiền không hợp lệ"));
        }

        log.info("ADMIN AUDIT | admin={} | action=TOPUP | target={} | amount={}",
                auth.adminUser.getEmail(), identifier, amount);

        try {
            Map<String, Object> res = adminService.topUp(identifier, amount);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/grant-crypto")
    public ResponseEntity<Map<String, Object>> grantCrypto(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                           @RequestBody Map<String, Object> body) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String identifier = extractTargetIdentifier(body);
        if (identifier == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Tên đăng nhập hoặc email không được để trống"));
        }

        String symbol = body.getOrDefault("symbol", "BTCUSDT").toString().trim().toUpperCase();
        BigDecimal quantity;
        BigDecimal avgPrice;
        try {
            quantity = new BigDecimal(body.getOrDefault("quantity", 1.0).toString().trim());
            avgPrice = new BigDecimal(body.getOrDefault("avgBuyPrice", 68000.0).toString().trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số lượng hoặc giá không hợp lệ"));
        }

        log.info("ADMIN AUDIT | admin={} | action=GRANT_CRYPTO | target={} | symbol={} | quantity={} | avgPrice={}",
                auth.adminUser.getEmail(), identifier, symbol, quantity, avgPrice);

        try {
            Map<String, Object> res = adminService.grantCrypto(identifier, symbol, quantity, avgPrice);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/set-balance")
    public ResponseEntity<Map<String, Object>> setBalance(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                          @RequestBody Map<String, Object> body) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String identifier = extractTargetIdentifier(body);
        if (identifier == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Tên đăng nhập hoặc email không được để trống"));
        }

        BigDecimal balance;
        try {
            balance = new BigDecimal(body.getOrDefault("balance", 100000).toString().trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Số dư không hợp lệ"));
        }

        log.info("ADMIN AUDIT | admin={} | action=SET_BALANCE | target={} | balance={}",
                auth.adminUser.getEmail(), identifier, balance);

        try {
            Map<String, Object> res = adminService.setBalance(identifier, balance);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetAccount(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                            @RequestBody Map<String, Object> body) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String identifier = extractTargetIdentifier(body);
        if (identifier == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Tên đăng nhập hoặc email không được để trống"));
        }

        log.info("ADMIN AUDIT | admin={} | action=RESET | target={}", auth.adminUser.getEmail(), identifier);

        try {
            Map<String, Object> res = adminService.resetAccount(identifier);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/db/query")
    @Profile({"dev", "local", "test"})
    public ResponseEntity<Map<String, Object>> executeDbQuery(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                              @RequestBody Map<String, String> body) {
        if (env != null && Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint is strictly disabled in PROD environment.");
        }

        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        String sql = body.getOrDefault("sql", "SELECT * FROM USERS").trim();
        String sqlUpper = sql.toUpperCase();

        if (sqlUpper.contains("UPDATE ") || sqlUpper.contains("DELETE ") ||
            sqlUpper.contains("DROP ") || sqlUpper.contains("TRUNCATE ") ||
            sqlUpper.contains("ALTER ") || sqlUpper.contains("INSERT ")) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", "Security Policy: Data manipulation commands (UPDATE/DELETE/DROP/etc) are strictly prohibited.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }

        Map<String, Object> res = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            res.put("status", "SUCCESS");
            res.put("type", "SELECT");
            res.put("rowCount", rows.size());
            res.put("data", rows);
        } catch (Exception e) {
            res.put("status", "ERROR");
            res.put("error", e.getMessage());
        }
        return ResponseEntity.ok(res);
    }

    @GetMapping("/db/overview")
    public ResponseEntity<Map<String, Object>> getDatabaseOverview(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        Map<String, Object> res = new HashMap<>();
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT id, email, full_name, avatar_url, role, created_at FROM USERS ORDER BY id ASC"
        );
        List<Map<String, Object>> wallets = jdbcTemplate.queryForList(
                "SELECT id, user_id, balance_usd, initial_balance, created_at, updated_at FROM WALLETS ORDER BY id ASC"
        );
        List<Map<String, Object>> holdings = jdbcTemplate.queryForList(
                "SELECT id, wallet_id, symbol, quantity, avg_buy_price, created_at, updated_at FROM HOLDINGS ORDER BY id ASC"
        );
        List<Map<String, Object>> transactions = jdbcTemplate.queryForList(
                "SELECT id, wallet_id, symbol, type, price, quantity, total_amount, created_at FROM TRANSACTIONS ORDER BY created_at DESC"
        );

        res.put("users", users);
        res.put("wallets", wallets);
        res.put("holdings", holdings);
        res.put("transactions", transactions);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getServerStatus(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        boolean isProd = env != null && Arrays.asList(env.getActiveProfiles()).contains("prod");

        Map<String, Object> res = new HashMap<>();
        res.put("serverPlatform", isProd ? "Railway Cloud Container (Linux x86_64)" : "Local/Dev Environment");
        res.put("totalUsers", adminService.getTotalUsers());
        res.put("usedRamMb", usedMemory + " MB");
        res.put("freeRamMb", freeMemory + " MB");
        res.put("totalRamAllocatedMb", totalMemory + " MB");
        res.put("databaseEngine", isProd ? "PostgreSQL Cloud (Railway)" : "H2 Persistent DB");
        res.put("uptime", "Running 24/7 Active");
        return ResponseEntity.ok(res);
    }

    @GetMapping("/news/diagnostics")
    public ResponseEntity<Map<String, Object>> getNewsDiagnostics(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AdminAuthResult auth = verifyAdmin(authHeader);
        if (!auth.isAuthorized()) return auth.toErrorResponse();

        Map<String, Object> diag = aiNewsService.getDiagnostics();
        if (env != null) {
            diag.put("activeProfiles", env.getActiveProfiles());
        }
        return ResponseEntity.ok(diag);
    }
}
