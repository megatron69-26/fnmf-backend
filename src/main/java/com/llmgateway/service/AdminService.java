package com.llmgateway.service;

import com.llmgateway.entity.User;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final com.llmgateway.repository.WalletLedgerRepository walletLedgerRepository;

    public AdminService(UserRepository userRepository,
                        WalletRepository walletRepository) {
        this(userRepository, walletRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminService(UserRepository userRepository,
                        WalletRepository walletRepository,
                        com.llmgateway.repository.WalletLedgerRepository walletLedgerRepository) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.walletLedgerRepository = walletLedgerRepository;
    }

    public User findUserByEmailOrId(String emailOrId) {
        if (emailOrId == null || emailOrId.isBlank()) {
            throw new IllegalArgumentException("Email người dùng không được để trống");
        }
        String clean = emailOrId.trim();

        // 1. Nếu là số ID người dùng (userId)
        if (clean.matches("^[0-9]+$")) {
            try {
                Long id = Long.parseLong(clean);
                Optional<User> userOpt = userRepository.findById(id);
                if (userOpt.isPresent()) {
                    return userOpt.get();
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2. Tìm kiếm chính xác case-insensitive theo email
        List<User> matches = userRepository.findAllByEmailIgnoreCase(clean);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy tài khoản: " + clean);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Phát hiện nhiều tài khoản trùng khớp với '" + clean + "', từ chối thao tác ngầm.");
        }
        return matches.get(0);
    }

    public User findUserByIdentifier(String identifier) {
        return findUserByEmailOrId(identifier);
    }

    @Transactional
    public Map<String, Object> updateUserEmail(String adminEmail, Long userId, String newEmailRaw) {
        if (userId == null) {
            throw new IllegalArgumentException("ID người dùng không được để trống");
        }
        if (newEmailRaw == null || newEmailRaw.isBlank()) {
            throw new IllegalArgumentException("Email mới không được để trống");
        }

        String normalizedEmail = AuthService.normalizeEmail(newEmailRaw);
        if (!AuthService.isValidEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Định dạng email mới không hợp lệ: " + newEmailRaw.trim());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng với ID: " + userId));

        // Kiểm tra xem email mới đã được tài khoản khác sử dụng hay chưa
        Optional<User> existing = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existing.isPresent() && !existing.get().getId().equals(userId)) {
            throw new IllegalArgumentException("Email '" + normalizedEmail + "' đã được sử dụng bởi tài khoản khác!");
        }

        String oldEmail = user.getEmail();
        user.setEmail(normalizedEmail);
        userRepository.save(user);

        log.info("ADMIN AUDIT | admin='{}' | action=UPDATE_EMAIL | userId={} | oldEmail='{}' | newEmail='{}'",
                adminEmail, userId, oldEmail, normalizedEmail);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Cập nhật email thành công!");
        res.put("userId", userId);
        res.put("oldEmail", oldEmail);
        res.put("newEmail", normalizedEmail);
        return res;
    }

    @Transactional
    public Map<String, Object> setBalance(String identifier, BigDecimal balance) {
        return setBalance("ADMIN", identifier, balance);
    }

    @Transactional
    public Map<String, Object> setBalance(String adminEmail, String identifier, BigDecimal balance) {
        if (balance == null || balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Số dư phải lớn hơn hoặc bằng 0");
        }
        User user = findUserByEmailOrId(identifier);
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa có ví tiền"));

        BigDecimal before = wallet.getBalanceUsd();
        BigDecimal diff = balance.subtract(before);

        wallet.forceSetBalance(balance);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        // Ghi Sổ cái (Ledger) kiểm toán bắt buộc đối với thao tác Admin
        com.llmgateway.entity.WalletLedger ledger = new com.llmgateway.entity.WalletLedger(
                wallet.getId(),
                null,
                com.llmgateway.entity.LedgerEntryType.ADMIN_ADJUSTMENT,
                diff,
                before,
                balance,
                "Admin adjustment by " + (adminEmail != null ? adminEmail : "ADMIN") + ": $" + before + " -> $" + balance
        );
        if (walletLedgerRepository != null) {
            walletLedgerRepository.save(ledger);
        }

        log.info(">>> [ADMIN] Đã đặt số dư tài khoản '{}' thành ${} (trước: ${}, chênh lệch: ${})",
                user.getEmail(), balance, before, diff);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã đặt số dư tài khoản thành $" + balance + " USD!");
        res.put("email", user.getEmail());
        res.put("userId", user.getId());
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        res.put("balanceBefore", before);
        res.put("difference", diff);
        return res;
    }
    
    public long getTotalUsers() {
        return userRepository.count();
    }
}
