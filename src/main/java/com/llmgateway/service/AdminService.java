package com.llmgateway.service;

import com.llmgateway.entity.Holding;
import com.llmgateway.entity.User;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
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
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    public AdminService(UserRepository userRepository,
                        WalletRepository walletRepository,
                        HoldingRepository holdingRepository,
                        TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
    }

    public User findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên đăng nhập hoặc email không được để trống");
        }
        String clean = identifier.trim();
        List<User> matches = userRepository.findByIdentifierMatches(clean);
        if (!matches.isEmpty()) {
            return matches.get(0);
        }
        return userRepository.findByEmailIgnoreCase(clean)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng: " + clean));
    }

    @Transactional
    public Map<String, Object> topUp(String identifier, BigDecimal amount) {
        User user = findUserByIdentifier(identifier);
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng: " + user.getEmail()));

        wallet.addFunds(amount);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        // Lưu log giao dịch ACID
        com.llmgateway.entity.Transaction tx = new com.llmgateway.entity.Transaction(
            wallet.getId(), "USD", "TOPUP", BigDecimal.ONE, amount, amount);
        transactionRepository.save(tx);

        log.info(">>> [ADMIN] Đã NẠP ${} vào ví userId={} (user: '{}')", amount, wallet.getId(), user.getEmail());

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Nạp tiền thành công!");
        res.put("email", user.getEmail());
        res.put("userId", user.getId());
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        return res;
    }

    @Transactional
    public Map<String, Object> grantCrypto(String identifier, String symbol, BigDecimal quantity, BigDecimal avgPrice) {
        User user = findUserByIdentifier(identifier);
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng: " + user.getEmail()));

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

        com.llmgateway.entity.Transaction tx = new com.llmgateway.entity.Transaction(
            wallet.getId(), symbol, "GRANT", avgPrice, quantity, quantity.multiply(avgPrice));
        transactionRepository.save(tx);

        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        log.info(">>> [ADMIN] Đã cấp {} {} (giá vốn ${}) cho user '{}'", quantity, symbol, avgPrice, user.getEmail());

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã cấp " + quantity + " " + symbol + " vào danh mục!");
        res.put("email", user.getEmail());
        res.put("symbol", symbol);
        res.put("totalQuantity", holding.getQuantity());
        res.put("avgBuyPrice", holding.getAvgBuyPrice());
        return res;
    }

    @Transactional
    public Map<String, Object> setBalance(String identifier, BigDecimal balance) {
        User user = findUserByIdentifier(identifier);
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng: " + user.getEmail()));

        wallet.forceSetBalance(balance);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        log.info(">>> [ADMIN] Đã đặt số dư tài khoản '{}' thành ${}", user.getEmail(), balance);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã đặt số dư tài khoản thành $" + balance + " USD!");
        res.put("email", user.getEmail());
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        return res;
    }

    @Transactional
    public Map<String, Object> resetAccount(String identifier) {
        User user = findUserByIdentifier(identifier);
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng: " + user.getEmail()));

        wallet.forceSetBalance(new BigDecimal("10000.0000"));
        wallet.setInitialBalance(new BigDecimal("10000.0000"));
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        holdingRepository.deleteByWalletId(wallet.getId());
        transactionRepository.deleteByWalletId(wallet.getId());

        log.info(">>> [ADMIN] Đã RESET toàn bộ tài khoản '{}' về $10,000 USD!", user.getEmail());

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã RESET ví về $10,000.00 USD và làm sạch danh mục!");
        res.put("email", user.getEmail());
        res.put("balanceUsd", wallet.getBalanceUsd());
        return res;
    }
    
    public long getTotalUsers() {
        return userRepository.count();
    }
}
