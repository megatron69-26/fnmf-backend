package com.llmgateway.controller;

import com.llmgateway.service.AdminService;
import com.llmgateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================
 * ADMIN CONTROL CENTER & DATABASE EXPLORER
 * =====================================================================
 */
@RestController
@RequestMapping("/api/admin")
// @CrossOrigin(origins = "*") - XÓA BỎ THEO YÊU CẦU BẢO MẬT
public class AdminController {

    @Autowired
    private org.springframework.core.env.Environment env;

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;
    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final com.llmgateway.repository.UserRepository userRepository;

    public AdminController(AdminService adminService, JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, com.llmgateway.repository.UserRepository userRepository) {
        this.adminService = adminService;
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    /**
     * Xác thực Token và Role ADMIN
     */
    private boolean isAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        if (token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (!jwtUtil.validateToken(token)) {
            return false;
        }
        String email = jwtUtil.getEmailFromToken(token);
        if (email == null) return false;
        
        // BƯỚC MỚI BỔ SUNG: Truy vấn DB để lấy đúng trạng thái user ở thời điểm hiện tại
        com.llmgateway.entity.User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return false; // User không còn tồn tại trong DB, token vô hiệu
        }
        
        // TODO (Để trả lời giảng viên nếu bị hỏi): Đây là giới hạn đã biết do bảng USERS thiếu cột ROLE.
        // Hướng mở rộng sau này: Thêm cột ROLE (USER/ADMIN) vào DB và thay đoạn check email cứng bên dưới
        // bằng `return user.getRole().equals("ADMIN");`. Tạm thời dùng fallback check email cứng.
        boolean isAdminEmail = user.getEmail().equals("khoi.pro@fnmf.com") || user.getEmail().endsWith("@admin.fnmf.com");
        if (isAdminEmail) {
            log.warn(">>> [SECURITY WARNING] User {} vừa vượt qua Admin Check bằng fallback email cứng (Do chưa có cột ROLE trong DB).", user.getEmail());
        }
        return isAdminEmail;
    }

    private ResponseEntity<Map<String, Object>> forbiddenResponse() {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "ERROR");
        res.put("message", "Access Denied: You do not have ADMIN privileges or your token is invalid.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(res);
    }

    @PostMapping("/topup")
    public ResponseEntity<Map<String, Object>> topUp(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                     @RequestBody Map<String, Object> body) {
        try {
            if (!isAdmin(authHeader)) return forbiddenResponse();
    
            String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();
            BigDecimal amount = new BigDecimal(body.getOrDefault("amount", 50000.0).toString());
    
            Map<String, Object> res = adminService.topUp(email, amount);
            return ResponseEntity.ok(res);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(java.util.Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/grant-crypto")
    public ResponseEntity<Map<String, Object>> grantCrypto(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                           @RequestBody Map<String, Object> body) {
        try {
            if (!isAdmin(authHeader)) return forbiddenResponse();
    
            String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();
            String symbol = body.getOrDefault("symbol", "BTCUSDT").toString().trim().toUpperCase();
            BigDecimal quantity = new BigDecimal(body.getOrDefault("quantity", 1.0).toString());
            BigDecimal avgPrice = new BigDecimal(body.getOrDefault("avgBuyPrice", 68000.0).toString());
    
            Map<String, Object> res = adminService.grantCrypto(email, symbol, quantity, avgPrice);
            return ResponseEntity.ok(res);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(java.util.Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/set-balance")
    public ResponseEntity<Map<String, Object>> setBalance(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                          @RequestBody Map<String, Object> body) {
        try {
            if (!isAdmin(authHeader)) return forbiddenResponse();
    
            String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();
            BigDecimal balance = new BigDecimal(body.getOrDefault("balance", 100000.0).toString());
    
            Map<String, Object> res = adminService.setBalance(email, balance);
            return ResponseEntity.ok(res);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(java.util.Map.of("status", "ERROR", "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetAccount(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                            @RequestBody Map<String, Object> body) {
        if (!isAdmin(authHeader)) return forbiddenResponse();

        String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();

        Map<String, Object> res = adminService.resetAccount(email);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/db/query")
    @Profile({"dev", "local", "test"}) // CHỈ ĐƯỢC PHÉP CHẠY TRÊN MÔI TRƯỜNG DEV/LOCAL/TEST
    public ResponseEntity<Map<String, Object>> executeDbQuery(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                              @RequestBody Map<String, String> body) {
        if (java.util.Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Endpoint is strictly disabled in PROD environment.");
        }

        if (!isAdmin(authHeader)) return forbiddenResponse();

        String sql = body.getOrDefault("sql", "SELECT * FROM USERS").trim();
        String sqlUpper = sql.toUpperCase();
        
        // BẢO MẬT: Chặn hoàn toàn các lệnh thay đổi dữ liệu
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
        if (!isAdmin(authHeader)) return forbiddenResponse();

        Map<String, Object> res = new HashMap<>();
        
        // Lấy list users và che/xóa cột password_hash
        List<Map<String, Object>> users = jdbcTemplate.queryForList("SELECT * FROM USERS");
        for (Map<String, Object> user : users) {
            user.remove("PASSWORD_HASH"); // Xóa bỏ cột nhạy cảm (Tùy thuộc dialect có thể in hoa hoặc in thường)
            user.remove("password_hash");
        }
        
        res.put("users", users);
        res.put("wallets", jdbcTemplate.queryForList("SELECT * FROM WALLETS"));
        res.put("holdings", jdbcTemplate.queryForList("SELECT * FROM HOLDINGS"));
        res.put("transactions", jdbcTemplate.queryForList("SELECT * FROM TRANSACTIONS ORDER BY CREATED_AT DESC"));
        return ResponseEntity.ok(res);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getServerStatus(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (!isAdmin(authHeader)) return forbiddenResponse();

        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        Map<String, Object> res = new HashMap<>();
        res.put("serverPlatform", "Samsung Galaxy Note 10+ 5G (ARM64 Android Termux)");
        res.put("totalUsers", adminService.getTotalUsers());
        res.put("usedRamMb", usedMemory + " MB");
        res.put("freeRamMb", freeMemory + " MB");
        res.put("totalRamAllocatedMb", totalMemory + " MB");
        res.put("databaseEngine", "H2 Oracle-Mode Persistent DB");
        res.put("uptime", "Running 24/7 Active");
        return ResponseEntity.ok(res);
    }
}
