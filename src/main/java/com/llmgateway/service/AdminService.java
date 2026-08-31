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

    @Transactional
    public Map<String, Object> topUp(String email, BigDecimal amount) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        wallet.addFunds(amount);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        // [KIáº¾N TRÃšC] Lưu log giao dịch ACID
        com.llmgateway.entity.Transaction tx = new com.llmgateway.entity.Transaction(
            wallet.getId(), "USD", "TOPUP", BigDecimal.ONE, amount, amount);
        transactionRepository.save(tx);

        log.info(">>> [ADMIN] Ä Ã£ NÄ P ${} vÃ o tÃ i khoáº£n '{}'", amount, email);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Nạp tiền thành công!");
        res.put("email", email);
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        return res;
    }

    @Transactional
    public Map<String, Object> grantCrypto(String email, String symbol, BigDecimal quantity, BigDecimal avgPrice) {
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

        // [KIáº¾N TRÃšC] Lưu log giao dịch ACID
        com.llmgateway.entity.Transaction tx = new com.llmgateway.entity.Transaction(
            wallet.getId(), symbol, "GRANT", avgPrice, quantity, quantity.multiply(avgPrice));
        transactionRepository.save(tx);

        log.info(">>> [ADMIN] Ä Ã£ cáº¥p {} {} (giÃ¡ vá»‘n ${}) cho user '{}'", quantity, symbol, avgPrice, email);

        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã cấp " + quantity + " " + symbol + " vào danh mục!");
        res.put("symbol", symbol);
        res.put("totalQuantity", holding.getQuantity());
        res.put("avgBuyPrice", holding.getAvgBuyPrice());
        return res;
    }

    @Transactional
    public Map<String, Object> setBalance(String email, BigDecimal balance) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        wallet.forceSetBalance(balance);
        wallet.setUpdatedAt(LocalDateTime.now());
        walletRepository.save(wallet);

        Map<String, Object> res = new HashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Đã đặt số dư tài khoản thành $" + balance + " USD!");
        res.put("newBalanceUsd", wallet.getBalanceUsd());
        return res;
    }

    @Transactional
    public Map<String, Object> resetAccount(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user: " + email));
        Wallet wallet = walletRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví"));

        wallet.forceSetBalance(new BigDecimal("10000.0000"));
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
        return res;
    }
    
    public long getTotalUsers() {
        return userRepository.count();
    }
}
