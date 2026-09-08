package com.llmgateway.service;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.trade.HoldingDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.dto.trade.PortfolioSummaryDto;
import com.llmgateway.entity.Holding;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.Wallet;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final TradeOrderExecutor tradeOrderExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    public TradeService(WalletRepository walletRepository,
                        HoldingRepository holdingRepository,
                        TransactionRepository transactionRepository,
                        MarketDataService marketDataService,
                        TradeOrderExecutor tradeOrderExecutor) {
        this.walletRepository = walletRepository;
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.marketDataService = marketDataService;
        this.tradeOrderExecutor = tradeOrderExecutor;
    }

    // Overloaded constructor for unit test convenience & backwards compatibility
    public TradeService(WalletRepository walletRepository,
                        HoldingRepository holdingRepository,
                        TransactionRepository transactionRepository,
                        MarketDataService marketDataService) {
        this(walletRepository, holdingRepository, transactionRepository, marketDataService,
                new TradeOrderExecutor(walletRepository, holdingRepository, transactionRepository));
    }

    // ====================================================================================
    // 🎓 [TÍNH TOÀN VẸN GIAO DỊCH, KHÓA BI QUAN & IDEMPOTENCY CHỐNG RACE CONDITION]
    // ------------------------------------------------------------------------------------
    // 1. Idempotency Key (clientOrderId):
    //    Bắt buộc phải có clientOrderId (UUID từ Android). Nếu cùng key retry, replay an toàn.
    //    Nếu cùng key mà payload thay đổi -> HTTP 409 Conflict.
    // 2. Outer Orchestrator + Inner Transactional Bean (REQUIRES_NEW):
    //    Không bắt DataIntegrityViolationException trong transaction đã rollback-only.
    //    Inner bean commit hoặc rollback độc lập; outer orchestrator xử lý recovery sạch sẽ.
    // 3. Price Authority:
    //    Từ chối giá null, <= 0 hoặc stale=true (MarketDataUnavailableException).
    // ====================================================================================
    public OrderResponse executeOrder(Long userId, OrderRequest request, MarketPriceDto priceDto) {
        // 1. Buộc clientOrderId hợp lệ cho order từ client mới
        String clientOrderId = request.getClientOrderId();
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId là bắt buộc để đảm bảo tính toàn vẹn giao dịch (idempotency)!");
        }
        clientOrderId = clientOrderId.trim();
        if (clientOrderId.length() > 64) {
            throw new IllegalArgumentException("clientOrderId không được vượt quá 64 ký tự!");
        }
        if (!clientOrderId.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            throw new IllegalArgumentException("clientOrderId không hợp lệ (chỉ chấp nhận ký tự chữ cái, số, gạch nối và gạch dưới tối đa 64 ký tự)!");
        }

        // 2. Kiểm tra giá thị trường thời gian thực (chống giá cũ / stale)
        if (priceDto == null || priceDto.getPrice() == null || priceDto.getPrice().compareTo(BigDecimal.ZERO) <= 0
                || Boolean.TRUE.equals(priceDto.getStale())) {
            throw new MarketDataUnavailableException(
                    "Dữ liệu thị trường thời gian thực không khả dụng để khớp lệnh (giá cũ/stale hoặc mất kết nối nhà cung cấp)!");
        }

        MarketSymbolConfig.validateSupported(request.getSymbol());
        String canonicalSymbol = MarketSymbolConfig.getCanonicalSymbol(request.getSymbol());
        String orderType = request.getType().trim().toUpperCase();
        BigDecimal quantity = request.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Khối lượng giao dịch phải lớn hơn 0!");
        }
        if (quantity.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("Khối lượng giao dịch tối đa 6 chữ số thập phân (chuẩn NUMERIC(18,6))!");
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng có ID: " + userId));

        // 3. Fast-check: Kiểm tra trước nếu lệnh đã được xử lý (tránh lock ví không cần thiết)
        Optional<Transaction> existingTxOpt = transactionRepository.findByWalletIdAndClientOrderId(wallet.getId(), clientOrderId);
        if (existingTxOpt.isPresent()) {
            Transaction tx = existingTxOpt.get();
            tradeOrderExecutor.verifyPayloadMatch(tx, canonicalSymbol, orderType, quantity);
            BigDecimal latestBalance = walletRepository.findById(wallet.getId())
                    .map(Wallet::getBalanceUsd)
                    .orElse(wallet.getBalanceUsd());
            log.info("REPLAYING IDEMPOTENT ORDER (FAST CHECK) | walletId={} | clientOrderId={} | txId={} | balance={}",
                    wallet.getId(), clientOrderId, tx.getId(), latestBalance);
            return tradeOrderExecutor.buildReplayResponse(tx, latestBalance);
        }

        // 4. Outer orchestrator gọi inner transactional executor (REQUIRES_NEW)
        try {
            return tradeOrderExecutor.executeTransactionalOrder(userId, request, canonicalSymbol, clientOrderId, priceDto);
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            // Khi có 2 luồng cạnh tranh cùng lúc, một luồng bị vi phạm ràng buộc Unique Index.
            // Do executeTransactionalOrder chạy trong REQUIRES_NEW, transaction outer này KHÔNG bị rollback-only.
            // Ta truy vấn lại giao dịch đã được commit an toàn bởi luồng chiến thắng:
            Optional<Transaction> racedTxOpt = transactionRepository.findByWalletIdAndClientOrderId(wallet.getId(), clientOrderId);
            if (racedTxOpt.isPresent()) {
                Transaction tx = racedTxOpt.get();
                tradeOrderExecutor.verifyPayloadMatch(tx, canonicalSymbol, orderType, quantity);
                BigDecimal latestBalance = walletRepository.findById(wallet.getId())
                        .map(Wallet::getBalanceUsd)
                        .orElse(wallet.getBalanceUsd());
                log.info("REPLAYING CONCURRENT RACED ORDER (RECOVERY) | walletId={} | clientOrderId={} | txId={} | balance={}",
                        wallet.getId(), clientOrderId, tx.getId(), latestBalance);
                return tradeOrderExecutor.buildReplayResponse(tx, latestBalance);
            }
            throw dive;
        }
    }

    public PortfolioSummaryDto getPortfolioSummary(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ví của người dùng!"));

        List<Holding> holdings = holdingRepository.findByWalletId(wallet.getId());
        List<HoldingDto> holdingDtos = new ArrayList<>();
        BigDecimal totalHoldingsValue = BigDecimal.ZERO;

        for (Holding h : holdings) {
            String sym = h.getSymbol();
            String displayName = MarketSymbolConfig.getDisplayName(sym);
            MarketPriceDto priceDto = marketDataService.getPriceBySymbol(sym);
            BigDecimal currentPrice = (priceDto != null && priceDto.getPrice() != null)
                    ? priceDto.getPrice()
                    : h.getAvgBuyPrice();

            BigDecimal investedAmount = h.getQuantity().multiply(h.getAvgBuyPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal currentValue = h.getQuantity().multiply(currentPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal profitLoss = currentValue.subtract(investedAmount).setScale(2, RoundingMode.HALF_UP);
            BigDecimal profitLossPct = investedAmount.compareTo(BigDecimal.ZERO) > 0
                    ? profitLoss.divide(investedAmount, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            totalHoldingsValue = totalHoldingsValue.add(currentValue);

            holdingDtos.add(new HoldingDto(
                    h.getId(),
                    sym,
                    displayName,
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
