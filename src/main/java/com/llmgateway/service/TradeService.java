package com.llmgateway.service;

import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.trade.HoldingDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.dto.trade.PortfolioSummaryDto;
import com.llmgateway.entity.Holding;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private final WalletRepository walletRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final MarketDataService marketDataService;

    public TradeService(WalletRepository walletRepository, HoldingRepository holdingRepository, TransactionRepository transactionRepository, MarketDataService marketDataService) {
        this.walletRepository = walletRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;
    }

    // ====================================================================================
    // 🎓 [CÂU HỎI BẢO VỆ ĐỒ ÁN: TÍNH TOÀN VẸN GIAO DỊCH & KHỚP LỆNH MUA/BÁN VÍ ẢO]
    // ------------------------------------------------------------------------------------
    // CÂU HỎI CỦA GIẢNG VIÊN:
    //   "Khi người dùng đặt lệnh MUA/BÁN, làm sao hệ thống đảm bảo tính toàn vẹn dữ liệu
    //    (ACID)? Tránh trường hợp tiền bị trừ nhưng tài sản chưa được cộng, hoặc số dư ví
    //    bị âm khi nhiều lệnh diễn ra đồng thời?"
    //
    // CÂU TRẢ LỜI CỦA MÃ NGUỒN (CODE TRẢ LỜI):
    //   1. ACID TRANSACTION: Đánh dấu @Transactional trên hàm executeOrder(). Toàn bộ
    //      thao tác (Trừ tiền ví -> Cập nhật danh mục Holdings -> Ghi lịch sử Transactions)
    //      nằm trong 1 Transaction duy nhất của Oracle DB. Nếu 1 bước lỗi, CSDL tự Rollback.
    //   2. KIỂM SOÁT SỐ DƯ & SỐ LƯỢNG: Kiểm tra `wallet.getBalanceUsd().compareTo(totalAmount) < 0`
    //      trước khi trừ tiền; kiểm tra `holding.getQuantity().compareTo(quantity) < 0` khi bán.
    //   3. CÔNG THỨC GIÁ MUA TRUNG BÌNH (DCA):
    //      newAvgPrice = (oldCost + newCost) / (oldQty + newQty).
    // ====================================================================================
    @Transactional
    public OrderResponse executeOrder(Long userId, OrderRequest request, MarketPriceDto priceDto) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng có ID: " + userId));

        String cleanSymbol = request.getSymbol().trim().toUpperCase();
        String orderType = request.getType().trim().toUpperCase();
        BigDecimal quantity = request.getQuantity();

        // [KIẾN TRÚC] Giá đã được lấy ở Controller (Ngoài Transaction) để tránh tình trạng Connection Pool Exhaustion.
        BigDecimal currentPrice = priceDto.getPrice();
        BigDecimal totalAmount = currentPrice.multiply(quantity).setScale(4, RoundingMode.HALF_UP);

        Transaction transaction;

        if ("BUY".equals(orderType)) {
            // [OOP] Sử dụng hàm nghiệp vụ đóng gói của Entity thay vì set public
            wallet.deductFunds(totalAmount);
            walletRepository.save(wallet);

            // Cập nhật danh mục tài sản sở hữu (HOLDINGS) theo công thức DCA
            Optional<Holding> holdingOpt = holdingRepository.findByWalletIdAndSymbol(wallet.getId(), cleanSymbol);
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
                Holding newHolding = new Holding(wallet.getId(), cleanSymbol, quantity, currentPrice);
                holdingRepository.save(newHolding);
            }

            // Ghi lịch sử giao dịch (TRANSACTIONS)
            transaction = new Transaction(wallet.getId(), cleanSymbol, "BUY", currentPrice, quantity, totalAmount);
            transactionRepository.save(transaction);

            log.info("LỆNH MUA KHỚP THÀNH CÔNG | userId={} | symbol={} | qty={} | price={} | total={}",
                    userId, cleanSymbol, quantity, currentPrice, totalAmount);

            return new OrderResponse(
                    transaction.getId(),
                    cleanSymbol,
                    "BUY",
                    quantity,
                    currentPrice,
                    totalAmount,
                    wallet.getBalanceUsd(),
                    LocalDateTime.now(),
                    "Khớp lệnh MUA thành công " + quantity + " " + cleanSymbol + "!"
            );

        } else if ("SELL".equals(orderType)) {
            // Lệnh BÁN: Kiểm tra số lượng tài sản đang nắm giữ trong CSDL
            Holding holding = holdingRepository.findByWalletIdAndSymbol(wallet.getId(), cleanSymbol)
                    .orElseThrow(() -> new IllegalArgumentException("Bạn chưa sở hữu tài sản " + cleanSymbol + " để bán!"));

            if (holding.getQuantity().compareTo(quantity) < 0) {
                throw new IllegalArgumentException(String.format(
                        "Số lượng %s hiện có (%s) không đủ để bán %s!",
                        cleanSymbol, holding.getQuantity(), quantity));
            }

            // [OOP] Sử dụng hàm nghiệp vụ đóng gói của Entity
            wallet.addFunds(totalAmount);
            walletRepository.save(wallet);

            // Trừ số lượng tài sản (Nếu bán hết thì xóa khỏi danh mục Holdings)
            BigDecimal remainingQty = holding.getQuantity().subtract(quantity);
            if (remainingQty.compareTo(BigDecimal.ZERO) == 0) {
                holdingRepository.delete(holding);
            } else {
                holding.setQuantity(remainingQty);
                holdingRepository.save(holding);
            }

            // Ghi lịch sử giao dịch (TRANSACTIONS)
            transaction = new Transaction(wallet.getId(), cleanSymbol, "SELL", currentPrice, quantity, totalAmount);
            transactionRepository.save(transaction);

            log.info("LỆNH BÁN KHỚP THÀNH CÔNG | userId={} | symbol={} | qty={} | price={} | total={}",
                    userId, cleanSymbol, quantity, currentPrice, totalAmount);

            return new OrderResponse(
                    transaction.getId(),
                    cleanSymbol,
                    "SELL",
                    quantity,
                    currentPrice,
                    totalAmount,
                    wallet.getBalanceUsd(),
                    LocalDateTime.now(),
                    "Khớp lệnh BÁN thành công " + quantity + " " + cleanSymbol + "!"
            );

        } else {
            throw new IllegalArgumentException("Loại lệnh không hợp lệ! Chỉ chấp nhận BUY hoặc SELL.");
        }
    }

    // ====================================================================================
    // 🎓 [CÂU HỎI BẢO VỆ ĐỒ ÁN: TÍNH LỜI/LỖ DANH MỤC THỜI GIAN THỰC - REALTIME PNL]
    // ------------------------------------------------------------------------------------
    // CÂU HỎI CỦA GIẢNG VIÊN:
    //   "Làm sao tính toán được Lời/Lỗ (PnL) và Tổng giá trị tài sản ròng (Net Worth)
    //    của người dùng theo biến động giá thị trường thời gian thực?"
    //
    // CÂU TRẢ LỜI CỦA MÃ NGUỒN (CODE TRẢ LỜI):
    //   1. Nạp danh sách Holdings từ Oracle DB.
    //   2. Với mỗi mã tài sản, lấy `currentPrice` mới nhất từ Alpha Vantage.
    //   3. Tính `unrealizedPnl = (currentPrice - avgBuyPrice) * quantity`.
    //   4. `totalNetWorth = cashBalance + sum(currentValue của từng Holding)`.
    // ====================================================================================
    public PortfolioSummaryDto getPortfolioSummary(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng có ID: " + userId));

        List<Holding> holdings = holdingRepository.findByWalletId(wallet.getId());
        List<HoldingDto> holdingDtos = new ArrayList<>();

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalHoldingsValue = BigDecimal.ZERO;

        for (Holding h : holdings) {
            MarketPriceDto priceDto = marketDataService.getPriceBySymbol(h.getSymbol());
            BigDecimal currentPrice = priceDto.getPrice();

            BigDecimal investedAmount = h.getQuantity().multiply(h.getAvgBuyPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal currentValue = h.getQuantity().multiply(currentPrice).setScale(2, RoundingMode.HALF_UP);

            BigDecimal profitLoss = currentValue.subtract(investedAmount).setScale(2, RoundingMode.HALF_UP);
            BigDecimal profitLossPct = investedAmount.compareTo(BigDecimal.ZERO) > 0
                    ? profitLoss.divide(investedAmount, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            totalInvested = totalInvested.add(investedAmount);
            totalHoldingsValue = totalHoldingsValue.add(currentValue);

            holdingDtos.add(new HoldingDto(
                    h.getId(),
                    h.getSymbol(),
                    priceDto.getName() != null ? priceDto.getName() : h.getSymbol(),
                    h.getQuantity(),
                    h.getAvgBuyPrice(),
                    currentPrice,
                    investedAmount,
                    currentValue,
                    profitLoss,
                    profitLossPct,
                    h.getUpdatedAt()
            ));
        }

        BigDecimal cashBalance = wallet.getBalanceUsd();
        BigDecimal initialBalance = wallet.getInitialBalance() != null ? wallet.getInitialBalance() : new BigDecimal("10000.00");
        BigDecimal totalNetWorth = cashBalance.add(totalHoldingsValue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalProfitLoss = totalNetWorth.subtract(initialBalance).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalProfitLossPct = initialBalance.compareTo(BigDecimal.ZERO) > 0
                ? totalProfitLoss.divide(initialBalance, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new PortfolioSummaryDto(
                cashBalance,
                initialBalance,
                totalHoldingsValue,
                totalNetWorth,
                totalProfitLoss,
                totalProfitLossPct,
                holdingDtos
        );
    }

    public List<Transaction> getTransactionHistory(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng!"));
        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
    }
}
