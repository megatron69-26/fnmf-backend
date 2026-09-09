package com.llmgateway;

import com.llmgateway.controller.PaymentController;
import com.llmgateway.controller.SandboxBankController;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.payment.CreatePaymentRequest;
import com.llmgateway.dto.payment.PaymentOrderResponseDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.entity.LedgerEntryType;
import com.llmgateway.entity.PaymentOrder;
import com.llmgateway.entity.PaymentStatus;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.entity.Wallet;
import com.llmgateway.entity.WalletLedger;
import com.llmgateway.exception.CheckoutExpiredException;
import com.llmgateway.exception.IdempotencyConflictException;
import com.llmgateway.exception.InsufficientBalanceException;
import com.llmgateway.exception.UnauthorizedException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.PaymentOrderRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletLedgerRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.util.JwtUtil;
import com.llmgateway.service.AdminService;
import com.llmgateway.service.PaymentService;
import com.llmgateway.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_sandbox_payment_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "alphavantage.api.key=test_api_key",
        "openai.api.key=test_api_key"
})
public class SandboxPaymentIntegrityTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AdminService adminService;

    @Autowired
    private TradeService tradeService;

    @Autowired
    private SandboxBankController sandboxBankController;

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private WalletLedgerRepository walletLedgerRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User userA;
    private User userB;
    private Wallet walletA;
    private Wallet walletB;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        holdingRepository.deleteAll();
        paymentOrderRepository.deleteAll();
        walletLedgerRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        userA = new User("userA@fnmf.com", "hash", "User A", UserRole.USER);
        userA = userRepository.save(userA);

        userB = new User("userB@fnmf.com", "hash", "User B", UserRole.USER);
        userB = userRepository.save(userB);

        walletA = new Wallet(userA.getId(), new BigDecimal("10000.00"));
        walletA = walletRepository.save(walletA);

        walletB = new Wallet(userB.getId(), new BigDecimal("5000.00"));
        walletB = walletRepository.save(walletB);
    }

    @Test
    @DisplayName("Deposit: Nạp tiền thành công cộng ví đúng 1 lần và ghi Sổ cái chính xác")
    void testDeposit_success_creditsWalletOnceAndCreatesLedger() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("500.00"), "dep-key-1");
        PaymentOrderResponseDto orderDto = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");

        assertNotNull(orderDto);
        assertEquals("PENDING", orderDto.getStatus());
        assertTrue(orderDto.getCheckoutUrl().contains("/sandbox-bank/checkout/"));

        PaymentOrder order = paymentOrderRepository.findById(orderDto.getPaymentOrderId()).orElseThrow();
        PaymentOrder updated = paymentService.processCheckoutAction(order.getCheckoutToken(), "SUCCESS", null);

        assertEquals(PaymentStatus.SUCCEEDED, updated.getStatus());

        Wallet updatedWallet = walletRepository.findById(walletA.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("10500.00").compareTo(updatedWallet.getBalanceUsd()));

        List<WalletLedger> ledgers = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertEquals(1, ledgers.size());
        WalletLedger ledger = ledgers.get(0);
        assertEquals(LedgerEntryType.DEPOSIT, ledger.getEntryType());
        assertEquals(0, new BigDecimal("500.00").compareTo(ledger.getAmountUsd()));
        assertEquals(0, new BigDecimal("10000.00").compareTo(ledger.getBalanceBefore()));
        assertEquals(0, new BigDecimal("10500.00").compareTo(ledger.getBalanceAfter()));
        assertEquals(order.getId(), ledger.getPaymentOrderId());
    }

    @Test
    @DisplayName("Withdrawal: Rút tiền thành công trừ ví và ghi Sổ cái số âm")
    void testWithdrawal_success_deductsWalletAndCreatesLedger() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("200.00"), "wdl-key-1");
        PaymentOrderResponseDto orderDto = paymentService.createWithdrawal(userA.getId(), req, "https://api.fnmf.com");

        assertNotNull(orderDto);
        assertEquals("PENDING", orderDto.getStatus());

        PaymentOrder order = paymentOrderRepository.findById(orderDto.getPaymentOrderId()).orElseThrow();
        PaymentOrder updated = paymentService.processCheckoutAction(order.getCheckoutToken(), "SUCCESS", null);

        assertEquals(PaymentStatus.SUCCEEDED, updated.getStatus());

        Wallet updatedWallet = walletRepository.findById(walletA.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("9800.00").compareTo(updatedWallet.getBalanceUsd()));

        List<WalletLedger> ledgers = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertEquals(1, ledgers.size());
        WalletLedger ledger = ledgers.get(0);
        assertEquals(LedgerEntryType.WITHDRAWAL, ledger.getEntryType());
        assertEquals(0, new BigDecimal("-200.00").compareTo(ledger.getAmountUsd()), "Số tiền rút trong sổ cái phải mang giá trị âm (-200.00)");
        assertEquals(0, new BigDecimal("10000.00").compareTo(ledger.getBalanceBefore()));
        assertEquals(0, new BigDecimal("9800.00").compareTo(ledger.getBalanceAfter()));
    }

    @Test
    @DisplayName("Withdrawal: Số dư không đủ ném InsufficientBalanceException (422)")
    void testWithdrawal_insufficientBalance_throwsException() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("15000.00"), "wdl-key-exceed");
        assertThrows(InsufficientBalanceException.class, () ->
                paymentService.createWithdrawal(userA.getId(), req, "https://api.fnmf.com"));
    }

    @Test
    @DisplayName("Concurrency: Hai request checkout đồng thời cùng token chỉ cộng tiền đúng một lần")
    void testConcurrentCheckoutApprovals_onlyOneSucceeds() throws InterruptedException, ExecutionException {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("300.00"), "race-key-1");
        PaymentOrderResponseDto orderDto = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");

        PaymentOrder order = paymentOrderRepository.findById(orderDto.getPaymentOrderId()).orElseThrow();
        String checkoutToken = order.getCheckoutToken();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<PaymentOrder> task = () -> {
            readyLatch.countDown();
            startLatch.await();
            return paymentService.processCheckoutAction(checkoutToken, "SUCCESS", null);
        };

        Future<PaymentOrder> f1 = executor.submit(task);
        Future<PaymentOrder> f2 = executor.submit(task);

        readyLatch.await();
        startLatch.countDown();

        PaymentOrder res1 = f1.get();
        PaymentOrder res2 = f2.get();
        executor.shutdown();

        assertNotNull(res1);
        assertNotNull(res2);
        assertEquals(PaymentStatus.SUCCEEDED, res1.getStatus());
        assertEquals(PaymentStatus.SUCCEEDED, res2.getStatus());

        Wallet finalWallet = walletRepository.findById(walletA.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("10300.00").compareTo(finalWallet.getBalanceUsd()),
                "Ví chỉ được cộng tiền đúng 1 lần (10300.00), không bị double-credit thành 10600.00!");

        List<WalletLedger> ledgers = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertEquals(1, ledgers.size(), "Chỉ được tạo đúng 1 bản ghi sổ cái duy nhất");
    }

    @Test
    @DisplayName("Concurrent Creation: Hai request tạo payment đồng thời cùng clientRequestId đều thành công trả về cùng orderId")
    void testConcurrentDepositCreation_sameClientRequestId_bothSucceedWithSameOrderId() throws InterruptedException, ExecutionException {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("400.00"), "concurrent-dep-req-id-123");
        String baseUrl = "https://api.fnmf.com";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<PaymentOrderResponseDto> task = () -> {
            readyLatch.countDown();
            startLatch.await();
            return paymentService.createDeposit(userA.getId(), req, baseUrl);
        };

        Future<PaymentOrderResponseDto> f1 = executor.submit(task);
        Future<PaymentOrderResponseDto> f2 = executor.submit(task);

        readyLatch.await();
        startLatch.countDown();

        PaymentOrderResponseDto res1 = f1.get();
        PaymentOrderResponseDto res2 = f2.get();
        executor.shutdown();

        assertNotNull(res1);
        assertNotNull(res2);
        assertEquals(res1.getPaymentOrderId(), res2.getPaymentOrderId(), "Cả hai request cạnh tranh phải trả về cùng mã đơn hàng");
        assertEquals(0, new BigDecimal("400.00").compareTo(res1.getAmountUsd()));

        long orderCount = paymentOrderRepository.findAllByUserIdOrderByCreatedAtDesc(userA.getId()).size();
        assertEquals(1, orderCount, "Chỉ tạo chính xác 1 đơn hàng trong cơ sở dữ liệu");
    }

    @Test
    @DisplayName("Idempotency: Replay nếu cùng clientRequestId, ném 409 nếu khác payload")
    void testIdempotency_replaysOrThrowsConflict() {
        CreatePaymentRequest req1 = new CreatePaymentRequest(new BigDecimal("100.00"), "idemp-test-key");
        PaymentOrderResponseDto res1 = paymentService.createDeposit(userA.getId(), req1, "https://api.fnmf.com");

        // Gọi lại lần 2 cùng tham số -> Replay
        PaymentOrderResponseDto res2 = paymentService.createDeposit(userA.getId(), req1, "https://api.fnmf.com");
        assertEquals(res1.getPaymentOrderId(), res2.getPaymentOrderId());

        // Gọi lần 3 cùng key nhưng khác số tiền -> Báo xung đột 409
        CreatePaymentRequest conflictReq = new CreatePaymentRequest(new BigDecimal("200.00"), "idemp-test-key");
        assertThrows(IdempotencyConflictException.class, () ->
                paymentService.createDeposit(userA.getId(), conflictReq, "https://api.fnmf.com"));
    }

    @Test
    @DisplayName("Expiry: Token hết hạn (15 phút) ném CheckoutExpiredException (410 GONE)")
    void testCheckoutToken_expiryThrows410Gone() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("50.00"), "expire-test-key");
        PaymentOrderResponseDto orderDto = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");

        PaymentOrder order = paymentOrderRepository.findById(orderDto.getPaymentOrderId()).orElseThrow();
        // Giả lập thời gian hết hạn trong quá khứ (1 phút trước)
        order.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        paymentOrderRepository.save(order);

        // 1. Kiểm tra mở trang checkout -> Bị từ chối 410 GONE
        assertThrows(CheckoutExpiredException.class, () ->
                paymentService.getOrderByCheckoutToken(order.getCheckoutToken()));

        ResponseEntity<String> pageResponse = sandboxBankController.getCheckoutPage(order.getCheckoutToken());
        assertEquals(HttpStatus.GONE, pageResponse.getStatusCode());
        assertTrue(pageResponse.getBody().contains("410 GONE"));

        // 2. Kiểm tra submit thanh toán -> Bị từ chối 410 GONE và chuyển trạng thái FAILED
        assertThrows(CheckoutExpiredException.class, () ->
                paymentService.processCheckoutAction(order.getCheckoutToken(), "SUCCESS", null));

        PaymentOrder expiredOrder = paymentOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, expiredOrder.getStatus());
        assertTrue(expiredOrder.getFailureReason().contains("hết hạn"));
    }

    @Test
    @DisplayName("Security: Token sai hoặc không tồn tại bị từ chối")
    void testInvalidCheckoutToken_rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                paymentService.processCheckoutAction("fake-non-existent-token", "SUCCESS", null));
    }

    @Test
    @DisplayName("Auth Security: Thiếu hoặc sai Bearer Token ném UnauthorizedException (401)")
    void testAuthValidation_missingOrInvalidToken_throwsUnauthorizedException() {
        // 1. Header null
        assertThrows(UnauthorizedException.class, () ->
                paymentController.getUserPayments(null, null));

        // 2. Header rỗng
        assertThrows(UnauthorizedException.class, () ->
                paymentController.getUserPayments("", null));

        // 3. Token không hợp lệ
        assertThrows(UnauthorizedException.class, () ->
                paymentController.getUserPayments("Bearer invalid_token_xyz", null));
    }

    @Test
    @DisplayName("CSP Compliance: Giao diện Hosted Checkout tuân thủ strict CSP, không inline style/script")
    void testHostedCheckoutHtml_strictlyCspCompliant() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("120.00"), "csp-test-key");
        PaymentOrderResponseDto orderDto = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");

        PaymentOrder order = paymentOrderRepository.findById(orderDto.getPaymentOrderId()).orElseThrow();
        ResponseEntity<String> response = sandboxBankController.getCheckoutPage(order.getCheckoutToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String html = response.getBody();
        assertNotNull(html);

        // Phải liên kết CSS và JS bên ngoài (compliant with 'self')
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"/sandbox-checkout.css\">"), "Phải tải CSS ngoài");
        assertTrue(html.contains("<script src=\"/sandbox-checkout.js\"></script>"), "Phải tải JS ngoài");

        // Tuyệt đối KHÔNG có inline style/script vi phạm CSP
        assertFalse(html.contains("<style>"), "Không được chứa thẻ <style> inline");
        assertFalse(html.contains("style=\""), "Không được chứa thuộc tính style=\"...\"");
        assertFalse(html.contains("onsubmit=\""), "Không được chứa event handler inline onsubmit");
    }

    @Test
    @DisplayName("Trading Audit: Giao dịch BUY và SELL ghi nhận chính xác vào Sổ cái (wallet_ledger)")
    void testTradingBuyAndSell_recordsInWalletLedger() {
        MarketPriceDto price = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO", new BigDecimal("50000.00"), BigDecimal.ZERO, new BigDecimal("49990.00"), new BigDecimal("50010.00"), "2026-09-09T08:00:00");

        // 1. Lệnh BUY: Mua 0.1 BTC = $5000.00 USD
        OrderRequest buyReq = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.100000"), "buy-tx-uuid-1");
        OrderResponse buyRes = tradeService.executeOrder(userA.getId(), buyReq, price);
        assertNotNull(buyRes);

        List<WalletLedger> ledgersAfterBuy = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertEquals(1, ledgersAfterBuy.size());
        WalletLedger buyLedger = ledgersAfterBuy.get(0);
        assertEquals(LedgerEntryType.TRADE_BUY, buyLedger.getEntryType());
        assertEquals(0, new BigDecimal("-5000.0000").compareTo(buyLedger.getAmountUsd()));
        assertEquals(0, new BigDecimal("10000.00").compareTo(buyLedger.getBalanceBefore()));
        assertEquals(0, new BigDecimal("5000.0000").compareTo(buyLedger.getBalanceAfter()));

        // 2. Lệnh SELL: Bán 0.05 BTC = $2500.00 USD
        OrderRequest sellReq = new OrderRequest("BTCUSDT", "SELL", new BigDecimal("0.050000"), "sell-tx-uuid-1");
        OrderResponse sellRes = tradeService.executeOrder(userA.getId(), sellReq, price);
        assertNotNull(sellRes);

        List<WalletLedger> ledgersAfterSell = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertEquals(2, ledgersAfterSell.size());
        WalletLedger sellLedger = ledgersAfterSell.get(0);
        assertEquals(LedgerEntryType.TRADE_SELL, sellLedger.getEntryType());
        assertEquals(0, new BigDecimal("2500.0000").compareTo(sellLedger.getAmountUsd()));
        assertEquals(0, new BigDecimal("5000.0000").compareTo(sellLedger.getBalanceBefore()));
        assertEquals(0, new BigDecimal("7500.0000").compareTo(sellLedger.getBalanceAfter()));
    }

    @Test
    @DisplayName("User Isolation: User A không thể xem order của User B")
    void testUserIsolation_cannotViewOtherUserOrder() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("50.00"), "order-user-b");
        PaymentOrderResponseDto orderB = paymentService.createDeposit(userB.getId(), req, "https://api.fnmf.com");

        assertThrows(IllegalArgumentException.class, () ->
                paymentService.getPaymentOrder(userA.getId(), orderB.getPaymentOrderId(), "https://api.fnmf.com"));
    }

    @Test
    @DisplayName("Terminal States: FAILED hoặc CANCELLED tuyệt đối không thay đổi số dư")
    void testFailedOrCancelled_doesNotChangeBalance() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("150.00"), "cancel-test-key");
        PaymentOrderResponseDto orderDto = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");

        PaymentOrder order = paymentOrderRepository.findById(orderDto.getPaymentOrderId()).orElseThrow();
        paymentService.processCheckoutAction(order.getCheckoutToken(), "CANCEL", null);

        Wallet updatedWallet = walletRepository.findById(walletA.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("10000.00").compareTo(updatedWallet.getBalanceUsd()),
                "Số dư ví phải giữ nguyên 10000.00 khi bị hủy");

        List<WalletLedger> ledgers = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertTrue(ledgers.isEmpty(), "Không được tạo ledger entry khi đơn hàng bị hủy");
    }

    @Test
    @DisplayName("Admin Audit: Admin set balance bắt buộc tạo Sổ cái loại ADMIN_ADJUSTMENT")
    void testAdminSetBalance_createsAdminAdjustmentLedger() {
        adminService.setBalance("admin@fnmf.com", userA.getEmail(), new BigDecimal("15000.00"));

        Wallet updatedWallet = walletRepository.findById(walletA.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("15000.00").compareTo(updatedWallet.getBalanceUsd()));

        List<WalletLedger> ledgers = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());
        assertEquals(1, ledgers.size());
        WalletLedger ledger = ledgers.get(0);
        assertEquals(LedgerEntryType.ADMIN_ADJUSTMENT, ledger.getEntryType());
        assertEquals(0, new BigDecimal("5000.00").compareTo(ledger.getAmountUsd()), "Chênh lệch số dư phải là 5000.00");
        assertEquals(0, new BigDecimal("10000.00").compareTo(ledger.getBalanceBefore()));
        assertEquals(0, new BigDecimal("15000.00").compareTo(ledger.getBalanceAfter()));
        assertTrue(ledger.getDescription().contains("admin@fnmf.com"));
    }

    @Test
    @DisplayName("Validation: Từ chối scale > 2 (cents), số âm, và số tiền vượt hạn mức Sandbox")
    void testValidation_rejectsInvalidAmount() {
        CreatePaymentRequest reqInvalidScale = new CreatePaymentRequest(new BigDecimal("10.123"), "scale-err");
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                paymentService.createDeposit(userA.getId(), reqInvalidScale, "https://api.fnmf.com"));
        assertTrue(ex1.getMessage().contains("tối đa 2 chữ số thập phân"));

        CreatePaymentRequest reqNegative = new CreatePaymentRequest(new BigDecimal("-10.00"), "neg-err");
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                paymentService.createDeposit(userA.getId(), reqNegative, "https://api.fnmf.com"));
        assertTrue(ex2.getMessage().contains("phải lớn hơn 0"));

        CreatePaymentRequest reqExceed = new CreatePaymentRequest(new BigDecimal("100000.01"), "exceed-err");
        IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, () ->
                paymentService.createDeposit(userA.getId(), reqExceed, "https://api.fnmf.com"));
        assertTrue(ex3.getMessage().contains("tối đa"));
    }

    @Test
    @DisplayName("Expiry via GET: Đơn hết hạn tự động chuyển FAILED khi gọi GET lịch sử hoặc chi tiết")
    void testExpiryViaGet_autoTransitionsPendingToFailed() {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("250.00"), "expire-get-test");
        PaymentOrderResponseDto created = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");

        PaymentOrder order = paymentOrderRepository.findById(created.getPaymentOrderId()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, order.getStatus());

        // Mô phỏng đã quá 15 phút
        order.setExpiresAt(LocalDateTime.now().minusMinutes(2));
        paymentOrderRepository.saveAndFlush(order);

        // 1. GET lịch sử danh sách: đơn phải tự động chuyển sang FAILED
        List<PaymentOrderResponseDto> history = paymentService.getUserPaymentOrders(userA.getId(), "https://api.fnmf.com");
        PaymentOrderResponseDto found = history.stream()
                .filter(dto -> dto.getPaymentOrderId().equals(created.getPaymentOrderId()))
                .findFirst().orElseThrow();
        assertEquals("FAILED", found.getStatus());
        assertTrue(found.getFailureReason().contains("hết hạn sau 15 phút"));

        // 2. Kiểm tra DB: trạng thái trong DB cũng đã cập nhật thành FAILED
        PaymentOrder inDb = paymentOrderRepository.findById(created.getPaymentOrderId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, inDb.getStatus());
        assertNotNull(inDb.getCompletedAt());

        // 3. GET chi tiết đơn đơn lẻ: trả về FAILED
        PaymentOrderResponseDto single = paymentService.getPaymentOrder(userA.getId(), created.getPaymentOrderId(), "https://api.fnmf.com");
        assertEquals("FAILED", single.getStatus());

        // 4. Mở checkout token khi đã hết hạn: trả về 410 CheckoutExpiredException
        assertThrows(CheckoutExpiredException.class, () ->
                paymentService.getOrderByCheckoutToken(inDb.getCheckoutToken()));
    }

    @Test
    @DisplayName("Concurrent Cancel vs Approve: Tranh chấp đồng thời Hủy lệnh và Xác nhận thanh toán")
    void testConcurrentCancelVsApprove_threadSafe() throws Exception {
        CreatePaymentRequest req = new CreatePaymentRequest(new BigDecimal("300.00"), "concurrent-cancel-approve");
        PaymentOrderResponseDto created = paymentService.createDeposit(userA.getId(), req, "https://api.fnmf.com");
        Long orderId = created.getPaymentOrderId();

        PaymentOrder order = paymentOrderRepository.findById(orderId).orElseThrow();
        String checkoutToken = order.getCheckoutToken();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // Luồng 1: Hủy lệnh
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.cancelPaymentOrder(userA.getId(), orderId, "https://api.fnmf.com");
            } catch (Exception ignored) {
                // Có thể bị từ chối nếu luồng approve đã hoàn tất trước và khóa dòng
            } finally {
                doneLatch.countDown();
            }
        });

        // Luồng 2: Xác nhận thanh toán thành công
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.processCheckoutAction(checkoutToken, "SUCCESS", null);
            } catch (Exception ignored) {
                // Có thể bị chặn nếu luồng cancel đã hoàn tất trước
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Kiểm tra tính nhất quán: trạng thái chỉ có thể là SUCCEEDED hoặc CANCELLED
        PaymentOrder finalOrder = paymentOrderRepository.findById(orderId).orElseThrow();
        Wallet finalWallet = walletRepository.findById(walletA.getId()).orElseThrow();
        List<WalletLedger> ledgers = walletLedgerRepository.findAllByWalletIdOrderByCreatedAtDesc(walletA.getId());

        if (finalOrder.getStatus() == PaymentStatus.SUCCEEDED) {
            // Nếu approve thắng: số dư tăng 300, sổ cái có 1 dòng DEPOSIT
            assertEquals(0, new BigDecimal("10300.00").compareTo(finalWallet.getBalanceUsd()));
            assertEquals(1, ledgers.size());
            assertEquals(LedgerEntryType.DEPOSIT, ledgers.get(0).getEntryType());
        } else {
            // Nếu cancel thắng: trạng thái CANCELLED, số dư giữ nguyên 10000.00, không có dòng ledger nào
            assertEquals(PaymentStatus.CANCELLED, finalOrder.getStatus());
            assertEquals(0, new BigDecimal("10000.00").compareTo(finalWallet.getBalanceUsd()));
            assertTrue(ledgers.isEmpty());
        }
    }
}