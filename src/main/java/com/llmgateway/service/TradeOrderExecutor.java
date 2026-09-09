package com.llmgateway.service;

import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.entity.Holding;
import com.llmgateway.entity.LedgerEntryType;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.Wallet;
import com.llmgateway.entity.WalletLedger;
import com.llmgateway.exception.IdempotencyConflictException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.WalletLedgerRepository;
import com.llmgateway.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
public class TradeOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(TradeOrderExecutor.class);

    private final WalletRepository walletRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final WalletLedgerRepository walletLedgerRepository;

    @Autowired
    public TradeOrderExecutor(WalletRepository walletRepository,
                              HoldingRepository holdingRepository,
                              TransactionRepository transactionRepository,
                              WalletLedgerRepository walletLedgerRepository) {
        this.walletRepository = walletRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.walletLedgerRepository = walletLedgerRepository;
    }

    public TradeOrderExecutor(WalletRepository walletRepository,
                              HoldingRepository holdingRepository,
                              TransactionRepository transactionRepository) {
        this(walletRepository, holdingRepository, transactionRepository, null);
    }

    /**
     * Thực thi lệnh trong một Transaction độc lập (REQUIRES_NEW).
     * Đảm bảo nếu vi phạm ràng buộc unique (raced request), transaction này rollback an toàn
     * mà không làm hỏng transaction của outer orchestrator.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResponse executeTransactionalOrder(Long userId, OrderRequest request, String canonicalSymbol,
                                                   String clientOrderId, MarketPriceDto priceDto) {
        String orderType = request.getType().trim().toUpperCase();
        BigDecimal quantity = request.getQuantity();
        BigDecimal currentPrice = priceDto.getPrice();
        BigDecimal totalAmount = currentPrice.multiply(quantity).setScale(4, RoundingMode.HALF_UP);

        // 1. Khóa bi quan bản ghi WALLETS
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng có ID: " + userId));

        // 2. Double-check Idempotency dưới khóa bi quan của ví
        if (clientOrderId != null) {
            Optional<Transaction> existingTxOpt = transactionRepository.findByWalletIdAndClientOrderId(wallet.getId(), clientOrderId);
            if (existingTxOpt.isPresent()) {
                Transaction tx = existingTxOpt.get();
                verifyPayloadMatch(tx, canonicalSymbol, orderType, quantity);
                log.info("REPLAYING IDEMPOTENT ORDER UNDER WALLET LOCK | walletId={} | clientOrderId={} | txId={}",
                        wallet.getId(), clientOrderId, tx.getId());
                return buildReplayResponse(tx, wallet.getBalanceUsd());
            }
        }

        Transaction transaction;

        if ("BUY".equals(orderType)) {
            BigDecimal balanceBefore = wallet.getBalanceUsd();
            // Trừ tiền ví ảo
            wallet.deductFunds(totalAmount);
            walletRepository.save(wallet);
            BigDecimal balanceAfter = wallet.getBalanceUsd();

            // Ghi nhận biến động số dư vào sổ cái kiểm toán (wallet_ledger)
            if (walletLedgerRepository != null) {
                WalletLedger ledger = new WalletLedger(
                        wallet.getId(),
                        null,
                        LedgerEntryType.TRADE_BUY,
                        totalAmount.negate(),
                        balanceBefore,
                        balanceAfter,
                        "Paper Trade BUY " + quantity + " " + canonicalSymbol + " @ " + currentPrice
                );
                walletLedgerRepository.save(ledger);
            }

            // Khóa và cập nhật bản ghi Holding
            Optional<Holding> holdingOpt = holdingRepository.findByWalletIdAndSymbolForUpdate(wallet.getId(), canonicalSymbol);
            if (holdingOpt.isPresent()) {
                Holding holding = holdingOpt.get();
                BigDecimal oldQty = holding.getQuantity();
                BigDecimal oldCost = oldQty.multiply(holding.getAvgBuyPrice());
                BigDecimal newQty = oldQty.add(quantity);
                BigDecimal newAvgPrice = (oldCost.add(totalAmount)).divide(newQty, 4, RoundingMode.HALF_UP);

                holding.setQuantity(newQty);
                holding.setAvgBuyPrice(newAvgPrice);
                holdingRepository.save(holding);
            } else {
                Holding newHolding = new Holding(wallet.getId(), canonicalSymbol, quantity, currentPrice);
                holdingRepository.save(newHolding);
            }

            transaction = new Transaction(wallet.getId(), canonicalSymbol, "BUY", currentPrice, quantity, totalAmount, clientOrderId);
        } else if ("SELL".equals(orderType)) {
            // Khóa và kiểm tra bản ghi Holding
            Holding holding = holdingRepository.findByWalletIdAndSymbolForUpdate(wallet.getId(), canonicalSymbol)
                    .orElseThrow(() -> new IllegalArgumentException("Bạn chưa sở hữu tài sản " + canonicalSymbol + " để bán!"));

            if (holding.getQuantity().compareTo(quantity) < 0) {
                throw new IllegalArgumentException(String.format("Số lượng %s hiện có (%s) không đủ để bán %s!",
                        canonicalSymbol, holding.getQuantity(), quantity));
            }

            // Trừ số lượng holding hoặc xóa nếu bán hết
            BigDecimal remainingQty = holding.getQuantity().subtract(quantity);
            if (remainingQty.compareTo(BigDecimal.ZERO) == 0) {
                holdingRepository.delete(holding);
            } else {
                holding.setQuantity(remainingQty);
                holdingRepository.save(holding);
            }

            BigDecimal balanceBefore = wallet.getBalanceUsd();
            // Cộng tiền vào ví
            wallet.addFunds(totalAmount);
            walletRepository.save(wallet);
            BigDecimal balanceAfter = wallet.getBalanceUsd();

            // Ghi nhận biến động số dư vào sổ cái kiểm toán (wallet_ledger)
            if (walletLedgerRepository != null) {
                WalletLedger ledger = new WalletLedger(
                        wallet.getId(),
                        null,
                        LedgerEntryType.TRADE_SELL,
                        totalAmount,
                        balanceBefore,
                        balanceAfter,
                        "Paper Trade SELL " + quantity + " " + canonicalSymbol + " @ " + currentPrice
                );
                walletLedgerRepository.save(ledger);
            }

            transaction = new Transaction(wallet.getId(), canonicalSymbol, "SELL", currentPrice, quantity, totalAmount, clientOrderId);
        } else {
            throw new IllegalArgumentException("Loại lệnh không hợp lệ! Chỉ chấp nhận BUY hoặc SELL.");
        }

        // Lưu transaction và flush để kích hoạt unique constraint ngay tại biên transaction này
        transaction = transactionRepository.saveAndFlush(transaction);

        return new OrderResponse(
                transaction.getId(),
                canonicalSymbol,
                orderType,
                currentPrice,
                quantity,
                totalAmount,
                wallet.getBalanceUsd(),
                transaction.getCreatedAt(),
                "Khớp lệnh thành công!"
        );
    }

    public void verifyPayloadMatch(Transaction tx, String expectedSymbol, String expectedType, BigDecimal expectedQuantity) {
        boolean symbolMatches = tx.getSymbol().equalsIgnoreCase(expectedSymbol);
        boolean typeMatches = tx.getType().equalsIgnoreCase(expectedType);
        boolean quantityMatches = tx.getQuantity().compareTo(expectedQuantity) == 0;

        if (!symbolMatches || !typeMatches || !quantityMatches) {
            log.warn("IDEMPOTENCY PAYLOAD CONFLICT | txId={} | expected=({}, {}, {}) | actual=({}, {}, {})",
                    tx.getId(), expectedSymbol, expectedType, expectedQuantity,
                    tx.getSymbol(), tx.getType(), tx.getQuantity());
            throw new IdempotencyConflictException("Idempotency key reused with different payload");
        }
    }

    public OrderResponse buildReplayResponse(Transaction tx, BigDecimal currentWalletBalance) {
        return new OrderResponse(
                tx.getId(),
                tx.getSymbol(),
                tx.getType(),
                tx.getPrice(),
                tx.getQuantity(),
                tx.getTotalAmount(),
                currentWalletBalance,
                tx.getCreatedAt(),
                "Lệnh đã được xử lý trước đó (Idempotent replay)."
        );
    }
}
