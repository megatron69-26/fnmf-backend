package com.llmgateway;

import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.entity.Holding;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.entity.Wallet;
import com.llmgateway.exception.IdempotencyConflictException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent Integration Test:
 * Kiểm thử tính Idempotent và chống Race Condition trên cơ sở dữ liệu thật (in-memory H2).
 * Xác minh 2 request đồng thời cùng clientOrderId chỉ tạo đúng 1 transaction, ví chỉ trừ tiền 1 lần.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_concurrent_trade_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "alphavantage.api.key=test_api_key",
        "openai.api.key=test_api_key"
})
public class ConcurrentTradeIdempotencyIntegrationTest {

    @Autowired
    private TradeService tradeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Long testUserId;
    private Long testWalletId;

    @BeforeEach
    public void setup() {
        transactionRepository.deleteAll();
        holdingRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User("concurrent_user@fnmf.com", "hashed_pwd", "Concurrent User", UserRole.USER);
        user = userRepository.save(user);
        testUserId = user.getId();

        Wallet wallet = new Wallet(testUserId, new BigDecimal("10000.0000"));
        wallet = walletRepository.save(wallet);
        testWalletId = wallet.getId();
    }

    @Test
    @DisplayName("Concurrent: Hai request đồng thời cùng clientOrderId chỉ khớp lệnh và trừ tiền 1 lần duy nhất")
    public void testConcurrentOrders_sameClientOrderId_deductsFundsOnlyOnce() throws Exception {
        String clientOrderId = "test-concurrent-" + UUID.randomUUID();
        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO",
                new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");
        OrderRequest request = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.1"), clientOrderId);

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<OrderResponse> task = () -> {
            readyLatch.countDown();
            startLatch.await(); // Đợi cả 2 luồng cùng sẵn sàng để phóng cùng mili-giây
            return tradeService.executeOrder(testUserId, request, priceDto);
        };

        Future<OrderResponse> future1 = executor.submit(task);
        Future<OrderResponse> future2 = executor.submit(task);

        readyLatch.await();
        startLatch.countDown(); // Bắn 2 request đồng thời

        OrderResponse res1 = future1.get();
        OrderResponse res2 = future2.get();
        executor.shutdown();

        assertNotNull(res1);
        assertNotNull(res2);

        // Cả 2 request phải trả về cùng ID giao dịch (một lệnh thật, một lệnh replay)
        assertEquals(res1.getTransactionId(), res2.getTransactionId(), "Cả 2 request phải có cùng transactionId");

        // Cả 2 request phải trả về CÙNG số dư ví mới (5,000.0000), không request nào được trả số dư cũ trước giao dịch (10,000.0000)
        assertEquals(new BigDecimal("5000.0000"), res1.getRemainingBalance(), "Request 1 phải trả số dư mới sau giao dịch");
        assertEquals(new BigDecimal("5000.0000"), res2.getRemainingBalance(), "Request 2 (replay/race) phải trả số dư mới sau giao dịch, không phải số dư cũ");
        assertEquals(res1.getRemainingBalance(), res2.getRemainingBalance(), "Cả 2 response phải nhận cùng remainingBalance");

        // Số dư ví chỉ được trừ đúng $5,000 (10,000 - 5,000 = 5,000, không bị trừ thành 0)
        Wallet wallet = walletRepository.findById(testWalletId).orElseThrow();
        assertEquals(new BigDecimal("5000.0000"), wallet.getBalanceUsd(), "Ví chỉ được trừ đúng 1 lần");

        // Holding chỉ có 0.1 BTC (không bị nhân đôi thành 0.2 BTC)
        Holding holding = holdingRepository.findByWalletIdAndSymbol(testWalletId, "BTCUSDT").orElseThrow();
        assertEquals(new BigDecimal("0.100000"), holding.getQuantity(), "Holding chỉ được cộng đúng 0.1 BTC");

        // CSDL chỉ lưu đúng 1 Transaction
        List<Transaction> transactions = transactionRepository.findByWalletIdOrderByCreatedAtDesc(testWalletId);
        assertEquals(1, transactions.size(), "Bảng TRANSACTIONS chỉ được phép có đúng 1 bản ghi");
        assertEquals(clientOrderId, transactions.get(0).getClientOrderId());

        // Kiểm tra Fast Replay tiếp theo: gọi lại lần 3 sau khi transaction đã commit
        OrderResponse resFastReplay = tradeService.executeOrder(testUserId, request, priceDto);
        assertNotNull(resFastReplay);
        assertEquals(res1.getTransactionId(), resFastReplay.getTransactionId(), "Fast replay phải trả về cùng transactionId");
        assertEquals(new BigDecimal("5000.0000"), resFastReplay.getRemainingBalance(), "Fast replay phải trả số dư mới nhất (5000.0000)");
    }

    @Test
    @DisplayName("Idempotency: Cùng clientOrderId nhưng thay đổi payload (quantity) bị từ chối HTTP 409 Conflict")
    public void testIdempotencyConflict_differentPayload_throwsConflict() {
        String clientOrderId = "test-conflict-" + UUID.randomUUID();
        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO",
                new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");

        // Lệnh 1: Mua 0.1 BTC
        OrderRequest req1 = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.1"), clientOrderId);
        OrderResponse res1 = tradeService.executeOrder(testUserId, req1, priceDto);
        assertNotNull(res1);

        // Lệnh 2: Tái sử dụng clientOrderId nhưng đổi quantity thành 0.2 BTC -> Bắt buộc ném IdempotencyConflictException
        OrderRequest req2 = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.2"), clientOrderId);
        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class, () ->
                tradeService.executeOrder(testUserId, req2, priceDto));

        assertTrue(ex.getMessage().contains("different payload"),
                "Thông báo lỗi phải chỉ rõ Idempotency key reused with different payload");
    }
}
