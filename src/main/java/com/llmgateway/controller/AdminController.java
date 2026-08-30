package com.llmgateway.controller;

import com.llmgateway.entity.Holding;
import com.llmgateway.entity.User;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * =====================================================================
 * ADMIN CONTROL CENTER & DATABASE EXPLORER
 * =====================================================================
 * Cung cấp:
 * 1. Quyền 'God Mode': Bơm tiền, nạp Coin, đặt số dư, reset ví.
 * 2. Visual Database Explorer: Xem trực tiếp bảng CSDL và chạy lệnh SQL
 * =====================================================================
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final JdbcTemplate jdbcTemplate;

    public AdminController(UserRepository userRepository,
                           WalletRepository walletRepository,
                           HoldingRepository holdingRepository,
                           TransactionRepository transactionRepository,
                           JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 1. NẠP THÊM TIỀN MẶT USD (DEPOSIT FAUCET)
     * POST /api/admin/deposit
     */
    @PostMapping("/deposit")
    @Transactional
    public ResponseEntity<Map<String, Object>> depositUsd(@RequestBody Map<String, Object> body) {
        String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();
        BigDecimal amount = new BigDecimal(body.getOrDefault("amount", 10000.0).toString());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        wallet.setBalanceUsd(wallet.getBalanceUsd().add(amount));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        log.info(">>> [ADMIN] Đã bơm +${} USD cho tài khoản '{}'. Số dư mới: ${}", amount, email, wallet.getBalanceUsd());

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã nạp thành công $" + amount + " USD vào tài khoản!");
        res.put("email", email);
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        return ResponseEntity.ok(res);
    }

    /**
     * 2. BƠM TRỰC TIẾP COIN / TÀI SẢN SỞ HỮU (GRANT CRYPTO HOLDINGS)
     * POST /api/admin/grant-crypto
     */
    @PostMapping("/grant-crypto")
    @Transactional
    public ResponseEntity<Map<String, Object>> grantCrypto(@RequestBody Map<String, Object> body) {
        String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();
        String symbol = body.getOrDefault("symbol", "BTCUSDT").toString().trim().toUpperCase();
        BigDecimal quantity = new BigDecimal(body.getOrDefault("quantity", 1.0).toString());
        BigDecimal avgPrice = new BigDecimal(body.getOrDefault("avgBuyPrice", 68000.0).toString());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        Optional<Holding> holdingOpt = holdingRepository.findByWalletIdAndSymbol(wallet.getId(), symbol);
        Holding holding;
        if (holdingOpt.isPresent()) {
            holding = holdingOpt.get();
            holding.setQuantity(holding.getQuantity().add(quantity));
            holding.setAvgBuyPrice(avgPrice);
            holding.setUpdatedAt(LocalDateTime.now());
        } else {
            holding = new Holding(wallet.getId(), symbol, quantity, avgPrice);
        }
        holdingRepository.save(holding);

        log.info(">>> [ADMIN] Đã cấp {} {} (giá vốn ${}) cho user '{}'", quantity, symbol, avgPrice, email);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã cấp " + quantity + " " + symbol + " vào danh mục!");
        res.put("symbol", symbol);
        res.put("totalQuantity", holding.getQuantity());
        res.put("avgBuyPrice", holding.getAvgBuyPrice());
        return ResponseEntity.ok(res);
    }

    /**
     * 3. THIẾT LẬP SỐ DƯ TÙY Ý (SET ARBITRARY BALANCE)
     * POST /api/admin/set-balance
     */
    @PostMapping("/set-balance")
    @Transactional
    public ResponseEntity<Map<String, Object>> setBalance(@RequestBody Map<String, Object> body) {
        String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();
        BigDecimal balance = new BigDecimal(body.getOrDefault("balance", 100000.0).toString());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        wallet.setBalanceUsd(balance);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã đặt số dư tài khoản thành $" + balance + " USD!");
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        return ResponseEntity.ok(res);
    }

    /**
     * 4. RESET TÀI KHOẢN VỀ GỐC ($10,000 USD, XÓA COIN & LỊCH SỬ)
     * POST /api/admin/reset
     */
    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetAccount(@RequestBody Map<String, Object> body) {
        String email = body.getOrDefault("email", "khoi.pro@fnmf.com").toString().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        wallet.setBalanceUsd(new BigDecimal("10000.0000"));
        wallet.setInitialBalance(new BigDecimal("10000.0000"));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        holdingRepository.deleteByWalletId(wallet.getId());
        transactionRepository.deleteByWalletId(wallet.getId());

        log.info(">>> [ADMIN] Đã RESET toàn bộ tài khoản '{}' về $10,000 USD!", email);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã RESET ví về $10,000.00 USD và làm sạch danh mục!");
        res.put("balanceUsd", wallet.getBalanceUsd());
        return ResponseEntity.ok(res);
    }

    /**
     * 5. TRÌNH XEM VÀ THỰC THI SQL TRỰC TIẾP TRÊN CSDL (DATABASE QUERY EXPLORER)
     * POST /api/admin/db/query
     * Body: { "sql": "SELECT * FROM WALLETS" }
     */
    @PostMapping("/db/query")
    public ResponseEntity<Map<String, Object>> executeDbQuery(@RequestBody Map<String, String> body) {
        String sql = body.getOrDefault("sql", "SELECT * FROM USERS").trim();
        Map<String, Object> res = new HashMap<>();
        try {
            if (sql.toUpperCase().startsWith("SELECT") || sql.toUpperCase().startsWith("SHOW")) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                res.put("status", "SUCCESS");
                res.put("type", "SELECT");
                res.put("rowCount", rows.size());
                res.put("data", rows);
            } else {
                int affected = jdbcTemplate.update(sql);
                res.put("status", "SUCCESS");
                res.put("type", "UPDATE/DDL");
                res.put("affectedRows", affected);
                res.put("message", "Thực thi thành công! Số dòng bị tác động: " + affected);
            }
        } catch (Exception e) {
            res.put("status", "ERROR");
            res.put("error", e.getMessage());
        }
        return ResponseEntity.ok(res);
    }

    /**
     * 6. LẤY TỔNG QUAN TẤT CẢ CÁC BẢNG TRONG CSDL
     * GET /api/admin/db/overview
     */
    @GetMapping("/db/overview")
    public ResponseEntity<Map<String, Object>> getDatabaseOverview() {
        Map<String, Object> res = new HashMap<>();
        res.put("users", jdbcTemplate.queryForList("SELECT * FROM USERS"));
        res.put("wallets", jdbcTemplate.queryForList("SELECT * FROM WALLETS"));
        res.put("holdings", jdbcTemplate.queryForList("SELECT * FROM HOLDINGS"));
        res.put("transactions", jdbcTemplate.queryForList("SELECT * FROM TRANSACTIONS ORDER BY CREATED_AT DESC"));
        return ResponseEntity.ok(res);
    }

    /**
     * 7. THÔNG SỐ SERVER NOTE 10+
     * GET /api/admin/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getServerStatus() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        Map<String, Object> res = new HashMap<>();
        res.put("serverPlatform", "Samsung Galaxy Note 10+ 5G (ARM64 Android Termux)");
        res.put("totalUsers", userRepository.count());
        res.put("usedRamMb", usedMemory + " MB");
        res.put("freeRamMb", freeMemory + " MB");
        res.put("totalRamAllocatedMb", totalMemory + " MB");
        res.put("databaseEngine", "H2 Oracle-Mode Persistent DB");
        res.put("uptime", "Running 24/7 Active");
        return ResponseEntity.ok(res);
    }
}
