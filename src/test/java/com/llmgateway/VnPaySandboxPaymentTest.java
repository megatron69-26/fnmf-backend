package com.llmgateway;

import com.llmgateway.config.VnPayConfig;
import com.llmgateway.controller.PaymentController;
import com.llmgateway.controller.VnPayPaymentController;
import com.llmgateway.dto.payment.CreatePaymentRequest;
import com.llmgateway.dto.payment.PaymentOrderResponseDto;
import com.llmgateway.entity.PaymentOrder;
import com.llmgateway.entity.PaymentStatus;
import com.llmgateway.entity.PaymentType;
import com.llmgateway.entity.User;
import com.llmgateway.entity.UserRole;
import com.llmgateway.entity.Wallet;
import com.llmgateway.repository.PaymentEventRepository;
import com.llmgateway.repository.PaymentOrderRepository;
import com.llmgateway.repository.UserRepository;
import com.llmgateway.repository.WalletLedgerRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.service.PaymentService;
import com.llmgateway.service.payment.InternalSandboxPaymentProvider;
import com.llmgateway.service.payment.PaymentProvider;
import com.llmgateway.service.payment.PaymentProviderRegistry;
import com.llmgateway.service.payment.VnPaySandboxPaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.util.JwtUtil;
import com.llmgateway.util.VnPayUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_vnpay_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "alphavantage.api.key=test_api_key",
        "openai.api.key=test_api_key",
        "payment.provider=VNPAY_SANDBOX",
        "payment.vnpay.tmn-code=DEMOVNPAY",
        "payment.vnpay.hash-secret=DEMOHASHSECRET1234567890ABCDEF",
        "payment.vnpay.pay-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
        "payment.vnpay.exchange-rate=25000"
})
public class VnPaySandboxPaymentTest {

    private static final String TEST_SECRET = "DEMOHASHSECRET1234567890ABCDEF";
    private static final String TEST_TMN = "DEMOVNPAY";

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private VnPayPaymentController vnPayPaymentController;

    @Autowired
    private PaymentProviderRegistry providerRegistry;

    @Autowired
    private VnPayConfig vnPayConfig;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private WalletLedgerRepository walletLedgerRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User testUser;
    private Wallet testWallet;

    @BeforeEach
    void setUp() {
        walletLedgerRepository.deleteAll();
        paymentEventRepository.deleteAll();
        paymentOrderRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User("vnpay.tester@fnmf.com", "hashed_pass", "VNPay Tester", UserRole.USER);
        testUser = userRepository.save(testUser);

        testWallet = new Wallet(testUser.getId(), new BigDecimal("1000.00"));
        testWallet = walletRepository.save(testWallet);
    }

    private Map<String, String> buildSignedIpnParams(PaymentOrder order, String responseCode, String transactionNo, long customAmount) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", TEST_TMN);
        params.put("vnp_Amount", String.valueOf(customAmount));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", String.valueOf(order.getId()));
        params.put("vnp_OrderInfo", "Nap tien FNMF #" + order.getId());
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", responseCode);
        params.put("vnp_TransactionNo", transactionNo);
        params.put("vnp_PayDate", "20260910120000");

        String hashData = VnPayUtil.buildHashData(params);
        String secureHash = VnPayUtil.hmacSHA512(TEST_SECRET, hashData);
        params.put("vnp_SecureHash", secureHash);
        return params;
    }

    private Map<String, String> buildSignedIpnParams(PaymentOrder order, String responseCode, String transactionNo) {
        BigDecimal expectedVnd = order.getAmountVnd();
        if (expectedVnd == null) {
            expectedVnd = order.getAmountUsd().multiply(new BigDecimal("25000")).setScale(0, RoundingMode.HALF_UP);
        }
        long vnpAmount = expectedVnd.multiply(new BigDecimal("100")).longValueExact();
        return buildSignedIpnParams(order, responseCode, transactionNo, vnpAmount);
    }

    private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual, "BigDecimal value should not be null");
        assertEquals(0, expected.compareTo(actual),
                String.format("Expected %s but was %s", expected, actual));
    }

    private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual, String message) {
        assertNotNull(actual, "BigDecimal value should not be null: " + message);
        assertEquals(0, expected.compareTo(actual),
                String.format("%s - Expected %s but was %s", message, expected, actual));
    }

    @Test
    @DisplayName("1. Tạo URL VNPay có chữ ký deterministic")
    void test1_deterministicUrlSigning() {
        Map<String, String> sampleParams = new HashMap<>();
        sampleParams.put("vnp_Version", "2.1.0");
        sampleParams.put("vnp_Command", "pay");
        sampleParams.put("vnp_TmnCode", "TESTCODE");
        sampleParams.put("vnp_Amount", "250000000");
        sampleParams.put("vnp_CurrCode", "VND");
        sampleParams.put("vnp_TxnRef", "12345");

        String hash1 = VnPayUtil.hmacSHA512("MY_SECRET_KEY", VnPayUtil.buildHashData(sampleParams));
        String hash2 = VnPayUtil.hmacSHA512("MY_SECRET_KEY", VnPayUtil.buildHashData(sampleParams));

        assertNotNull(hash1);
        assertEquals(hash1, hash2, "HMAC-SHA512 phải deterministic");
        assertEquals(128, hash1.length(), "HMAC-SHA512 hex string phải có độ dài 128 ký tự");
    }

    @Test
    @DisplayName("2. vnp_Amount được nhân 100 chính xác từ số tiền VND")
    void test2_vnpAmountMultipliedBy100() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_test_amount_100");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        assertNotNull(dto);
        assertBigDecimalEquals(new BigDecimal("100.00"), dto.getAmountUsd());
        assertBigDecimalEquals(new BigDecimal("2500000"), dto.getAmountVnd());
        assertBigDecimalEquals(new BigDecimal("25000.0000"), dto.getExchangeRateSnapshot());

        assertNotNull(dto.getCheckoutUrl());
        assertTrue(dto.getCheckoutUrl().contains("vnp_Amount=250000000"), "vnp_Amount phải là VND * 100 (250,000,000)");
    }

    @Test
    @DisplayName("3. Secret không xuất hiện trong checkoutUrl hoặc response DTO")
    void test3_secretNotExposedInResponseOrDto() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("50.00"));
        req.setClientRequestId("dep_secret_leak_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");

        assertFalse(dto.getCheckoutUrl().contains(TEST_SECRET), "checkoutUrl không được lộ HashSecret");
        assertFalse(dto.toString().contains(TEST_SECRET), "DTO không được chứa HashSecret");
    }

    @Test
    @DisplayName("4. IPN hash hợp lệ cộng vốn đúng một lần duy nhất")
    void test4_validIpnCreditsWalletOnce() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("200.00"));
        req.setClientRequestId("dep_ipn_valid_credit");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNPAY_TXN_9999");
        ResponseEntity<Map<String, String>> ipnResponse = vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertEquals("00", ipnResponse.getBody().get("RspCode"));
        assertEquals("Confirm Success", ipnResponse.getBody().get("Message"));

        Wallet updatedWallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1200.00"), updatedWallet.getBalanceUsd(), "Ví phải được cộng đúng 200.00 USD");

        PaymentOrder updatedOrder = paymentOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCEEDED, updatedOrder.getStatus());
        assertNotNull(updatedOrder.getPaidAt());
        assertEquals("VNPAY_TXN_9999", updatedOrder.getProviderTransactionNo());
    }

    @Test
    @DisplayName("5. IPN replay không cộng vốn lần hai (Idempotent, RspCode 02)")
    void test5_ipnReplayDoesNotCreditTwice() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("300.00"));
        req.setClientRequestId("dep_ipn_replay_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNPAY_TXN_REPLAY");

        // Lần 1: Thành công
        ResponseEntity<Map<String, String>> resp1 = vnPayPaymentController.handleVnPayIpn(ipnParams);
        assertEquals("00", resp1.getBody().get("RspCode"));

        // Lần 2: Replay
        ResponseEntity<Map<String, String>> resp2 = vnPayPaymentController.handleVnPayIpn(ipnParams);
        assertEquals("02", resp2.getBody().get("RspCode"));
        assertEquals("Order already confirmed", resp2.getBody().get("Message"));

        Wallet updatedWallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1300.00"), updatedWallet.getBalanceUsd(), "Ví chỉ được cộng đúng 1 lần");
    }

    @Test
    @DisplayName("6. Hai IPN đồng thời chỉ một request được hạch toán")
    void test6_concurrentIpnsOnlyOneCredits() throws InterruptedException, ExecutionException {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_ipn_concurrent");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNPAY_TXN_RACE");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<ResponseEntity<Map<String, String>>> task = () -> {
            startLatch.await();
            return vnPayPaymentController.handleVnPayIpn(ipnParams);
        };

        Future<ResponseEntity<Map<String, String>>> f1 = executor.submit(task);
        Future<ResponseEntity<Map<String, String>>> f2 = executor.submit(task);

        startLatch.countDown();

        ResponseEntity<Map<String, String>> r1 = f1.get();
        ResponseEntity<Map<String, String>> r2 = f2.get();
        executor.shutdown();

        boolean oneSucceeded = ("00".equals(r1.getBody().get("RspCode")) && "02".equals(r2.getBody().get("RspCode")))
                || ("02".equals(r1.getBody().get("RspCode")) && "00".equals(r2.getBody().get("RspCode")));

        assertTrue(oneSucceeded, "Một luồng phải trả 00 và luồng kia trả 02");

        Wallet updatedWallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1100.00"), updatedWallet.getBalanceUsd(), "Số dư chỉ được cộng chính xác một lần");
    }

    @Test
    @DisplayName("7. Sai chữ ký số không thay đổi số dư ví (RspCode 97)")
    void test7_invalidChecksumRejected() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("150.00"));
        req.setClientRequestId("dep_invalid_hash");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNPAY_FAKE_HASH");
        ipnParams.put("vnp_SecureHash", "INVALID_TAMPERED_CHECKSUM_VALUE_12345");

        ResponseEntity<Map<String, String>> resp = vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertEquals("97", resp.getBody().get("RspCode"));
        assertEquals("Invalid Checksum", resp.getBody().get("Message"));

        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd(), "Ví không được thay đổi khi sai chữ ký");

        PaymentOrder unchangedOrder = paymentOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, unchangedOrder.getStatus());
    }

    @Test
    @DisplayName("8. Sai amount không thay đổi số dư ví (RspCode 04)")
    void test8_invalidAmountRejected() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_invalid_amount");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        // Giả lập hacker gửi vnp_Amount sai (50,000,000 thay vì 250,000,000)
        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNP_WRONG_AMT", 50000000L);

        ResponseEntity<Map<String, String>> resp = vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertEquals("04", resp.getBody().get("RspCode"));
        assertEquals("Invalid amount", resp.getBody().get("Message"));

        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());
    }

    @Test
    @DisplayName("9. Sai TmnCode không thay đổi số dư ví (RspCode 01)")
    void test9_invalidTmnCodeRejected() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_invalid_tmn");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNP_WRONG_TMN");
        ipnParams.put("vnp_TmnCode", "WRONG_MERCHANT_CODE");
        // Re-hash with wrong TMN code
        String hashData = VnPayUtil.buildHashData(ipnParams);
        ipnParams.put("vnp_SecureHash", VnPayUtil.hmacSHA512(TEST_SECRET, hashData));

        ResponseEntity<Map<String, String>> resp = vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertEquals("01", resp.getBody().get("RspCode"));
        assertEquals("Order not Found", resp.getBody().get("Message"));

        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());
    }

    @Test
    @DisplayName("10. Đơn hết hạn không thay đổi số dư ví")
    void test10_expiredOrderRejected() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_expired_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        // Chỉnh sửa thời gian hết hạn về quá khứ
        order.setExpiresAt(LocalDateTime.now().minusMinutes(20));
        paymentOrderRepository.save(order);

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNP_EXPIRED_TXN");
        ResponseEntity<Map<String, String>> resp = vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertEquals("02", resp.getBody().get("RspCode"));

        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());

        PaymentOrder updatedOrder = paymentOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, updatedOrder.getStatus());
    }

    @Test
    @DisplayName("11. Return URL không thay đổi số dư ví")
    void test11_returnUrlDoesNotAlterWallet() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_return_url_view");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> returnParams = buildSignedIpnParams(order, "00", "VNP_RETURN_CALL");

        // Gọi Return URL
        ResponseEntity<String> htmlResp = vnPayPaymentController.handleVnPayReturn(returnParams);

        assertEquals(200, htmlResp.getStatusCode().value());
        assertTrue(htmlResp.getBody().contains("fnmf://payment/return"), "HTML phải chứa link deep link quay lại app");
        assertTrue(htmlResp.getBody().contains("MÔI TRƯỜNG SANDBOX — KHÔNG PHẢI TIỀN THẬT"), "HTML phải chứa cảnh báo Sandbox");

        // Tuyệt đối không thay đổi số dư ví
        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd(), "Return URL tuyệt đối không được cộng tiền vào ví");

        PaymentOrder orderAfterReturn = paymentOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, orderAfterReturn.getStatus(), "Trạng thái đơn vẫn phải là PENDING trước khi có IPN");
    }

    @Test
    @DisplayName("12. PaymentEvent và WalletLedger được tạo đúng khi IPN thành công")
    void test12_paymentEventAndWalletLedgerCreated() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("500.00"));
        req.setClientRequestId("dep_ledger_event_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNP_LEDGER_777");
        vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertTrue(walletLedgerRepository.existsByPaymentOrderId(order.getId()), "Phải có bản ghi WalletLedger");
        assertTrue(paymentEventRepository.count() > 0, "Phải ghi nhận PaymentEvent");
    }

    @Test
    @DisplayName("13. Provider chưa cấu hình secret phải fail-fast an toàn")
    void test13_unconfiguredSecretFailsFast() {
        VnPayConfig unconfigured = new VnPayConfig();
        unconfigured.setTmnCode("");
        unconfigured.setHashSecret("");

        VnPaySandboxPaymentProvider provider = new VnPaySandboxPaymentProvider(unconfigured);
        PaymentOrder dummyOrder = new PaymentOrder();
        dummyOrder.setId(99L);
        dummyOrder.setAmountUsd(new BigDecimal("100.00"));
        dummyOrder.setCheckoutToken("dummy_token");

        assertThrows(IllegalStateException.class, () -> {
            provider.buildCheckoutUrl(dummyOrder, "https://api.fnmf.com");
        });
    }

    @Test
    @DisplayName("14. Dual provider registry hỗ trợ VNPAY_SANDBOX và SANDBOX_INTERNAL; Rút tiền luôn dùng SANDBOX_INTERNAL")
    void test14_dualProviderConfiguration() {
        PaymentProvider vnpay = providerRegistry.getProvider("VNPAY_SANDBOX");
        assertNotNull(vnpay);
        assertEquals("VNPAY_SANDBOX", vnpay.getProviderName());

        PaymentProvider internal = providerRegistry.getProvider("SANDBOX_INTERNAL");
        assertNotNull(internal);
        assertEquals("SANDBOX_INTERNAL", internal.getProviderName());

        PaymentProvider defaultProv = providerRegistry.getDefaultProvider();
        assertEquals("SANDBOX_INTERNAL", defaultProv.getProviderName());

        // Rút tiền luôn dùng internal
        CreatePaymentRequest wdlReq = new CreatePaymentRequest();
        wdlReq.setAmountUsd(new BigDecimal("50.00"));
        wdlReq.setClientRequestId("wdl_provider_check");

        PaymentOrderResponseDto wdlDto = paymentService.createWithdrawal(testUser.getId(), wdlReq, "https://api.fnmf.com");
        assertEquals("SANDBOX_INTERNAL", wdlDto.getProvider(), "Rút tiền phải luôn sử dụng SANDBOX_INTERNAL");
        assertTrue(wdlDto.getCheckoutUrl().contains("/sandbox-bank/checkout/"), "Rút tiền trỏ vào checkout nội bộ");
    }

    @Test
    @DisplayName("15. Cổng nội bộ sandbox từ chối xử lý hoặc xem đơn hàng VNPAY_SANDBOX")
    void test15_internalCheckoutRejectsVnPayOrder() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("100.00"));
        req.setClientRequestId("dep_vnpay_internal_block_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        assertEquals("VNPAY_SANDBOX", dto.getProvider());
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();
        String checkoutToken = order.getCheckoutToken();

        // 1. Không cho phép xem đơn VNPay qua endpoint checkout nội bộ
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () -> {
            paymentService.getOrderByCheckoutToken(checkoutToken);
        });
        assertTrue(ex1.getMessage().contains("SANDBOX_INTERNAL"), "Phải chặn đơn không phải SANDBOX_INTERNAL");

        // 2. Không cho phép kích hoạt cộng tiền qua endpoint action nội bộ
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () -> {
            paymentService.processCheckoutAction(checkoutToken, "SUCCESS", null);
        });
        assertTrue(ex2.getMessage().contains("SANDBOX_INTERNAL"), "Phải chặn action nội bộ cho đơn VNPay");

        // 3. Số dư ví không được thay đổi
        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());
    }

    @Test
    @DisplayName("16. IPN thiếu hoặc sai vnp_TransactionStatus sẽ đánh dấu đơn FAILED và không cộng tiền")
    void test16_ipnRejectsMissingTransactionStatus() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("120.00"));
        req.setClientRequestId("dep_missing_status_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        // Chuẩn bị IPN có vnp_ResponseCode = "00" nhưng thiếu vnp_TransactionStatus
        Map<String, String> ipnParams = buildSignedIpnParams(order, "00", "VNP_NO_STATUS_TXN");
        ipnParams.remove("vnp_TransactionStatus");
        String hashData = VnPayUtil.buildHashData(ipnParams);
        ipnParams.put("vnp_SecureHash", VnPayUtil.hmacSHA512(TEST_SECRET, hashData));

        ResponseEntity<Map<String, String>> resp = vnPayPaymentController.handleVnPayIpn(ipnParams);
        assertEquals("00", resp.getBody().get("RspCode"));

        // Xác nhận đơn bị FAILED
        PaymentOrder updatedOrder = paymentOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, updatedOrder.getStatus());
        assertTrue(updatedOrder.getFailureReason().contains("TransactionStatus"));

        // Ví giữ nguyên 1000.00 USD
        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());
    }

    @Test
    @DisplayName("17. IPN VNPay từ chối đơn hàng thuộc provider khác hoặc không phải loại DEPOSIT (RspCode 01)")
    void test17_ipnRejectsNonVnPayOrderOrNonDeposit() {
        // Tạo một đơn rút tiền (provider = SANDBOX_INTERNAL, type = WITHDRAWAL)
        CreatePaymentRequest wdlReq = new CreatePaymentRequest();
        wdlReq.setAmountUsd(new BigDecimal("40.00"));
        wdlReq.setClientRequestId("wdl_vnpay_spoof_check");

        PaymentOrderResponseDto wdlDto = paymentService.createWithdrawal(testUser.getId(), wdlReq, "https://api.fnmf.com");
        PaymentOrder wdlOrder = paymentOrderRepository.findById(wdlDto.getPaymentOrderId()).orElseThrow();

        Map<String, String> ipnParams = buildSignedIpnParams(wdlOrder, "00", "VNP_SPOOF_WDL");
        ResponseEntity<Map<String, String>> resp = vnPayPaymentController.handleVnPayIpn(ipnParams);

        assertEquals("01", resp.getBody().get("RspCode"));
        assertEquals("Order not Found", resp.getBody().get("Message"));

        // Ví không bị thay đổi bởi IPN giả mạo
        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());
    }

    @Test
    @DisplayName("18. PaymentProviderRegistry fail-fast khi provider rỗng hoặc không tồn tại")
    void test18_providerRegistryFailsFastOnUnknownProvider() {
        IllegalArgumentException exUnknown = assertThrows(IllegalArgumentException.class, () -> {
            providerRegistry.getProvider("UNKNOWN_GATEWAY_XYZ");
        });
        assertTrue(exUnknown.getMessage().contains("UNKNOWN_GATEWAY_XYZ"));

        assertThrows(IllegalArgumentException.class, () -> providerRegistry.getProvider(""));
        assertThrows(IllegalArgumentException.class, () -> providerRegistry.getProvider("   "));
        assertThrows(IllegalArgumentException.class, () -> providerRegistry.getProvider(null));
    }

    @Test
    @DisplayName("19. Return HTML hiển thị ĐANG XÁC NHẬN khi chưa có IPN và escape XSS an toàn")
    void test19_returnHtmlShowsPendingWhenOrderNotYetCreditedAndEscapesXss() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("75.00"));
        req.setClientRequestId("dep_xss_protection_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        // Gửi params chứa payload XSS nguy hiểm
        Map<String, String> returnParams = buildSignedIpnParams(order, "00", "TXN<script>alert('xss')</script>");
        ResponseEntity<String> htmlResp = vnPayPaymentController.handleVnPayReturn(returnParams);

        assertEquals(200, htmlResp.getStatusCode().value());
        String html = htmlResp.getBody();
        assertNotNull(html);

        // Do chưa có IPN, trạng thái trong DB vẫn là PENDING -> phải hiển thị ĐANG XÁC NHẬN
        assertTrue(html.contains("ĐANG XÁC NHẬN"), "Phải hiển thị ĐANG XÁC NHẬN khi IPN chưa tới");
        assertFalse(html.contains("THÀNH CÔNG"), "Không được hiển thị THÀNH CÔNG khi chưa hạch toán IPN");

        // Payload XSS phải được escape an toàn
        assertFalse(html.contains("<script>alert('xss')</script>"), "HTML không được chứa script tag chưa escape");
        assertTrue(html.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"), "Script tag phải được escape thành HTML entities");

        // Tuân thủ CSP: không chứa <style> inline hoặc thuộc tính style=""
        assertFalse(html.contains("<style"), "HTML không được chứa thẻ <style> inline vi phạm CSP");
        assertFalse(html.contains("style="), "HTML không được chứa inline style attributes vi phạm CSP");
        assertTrue(html.contains("/vnpay-return.css"), "HTML phải liên kết stylesheet tĩnh /vnpay-return.css");
        assertTrue(html.contains("status-badge status-pending"), "HTML phải dùng CSS class status-pending");
    }

    @Test
    @DisplayName("20. Phân giải Client IP từ các Proxy headers (X-Forwarded-For, IPv6 normalize) và chuyển vào checkout URL")
    void test20_clientIpExtractionAndForwarding() {
        // 1. Phân giải IP từ multi-value X-Forwarded-For
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        req1.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
        assertEquals("203.0.113.195", PaymentController.resolveClientIp(req1));

        // 2. Phân giải IP từ X-Real-IP
        MockHttpServletRequest req2 = new MockHttpServletRequest();
        req2.addHeader("X-Real-IP", "198.51.100.42");
        assertEquals("198.51.100.42", PaymentController.resolveClientIp(req2));

        // 3. Chuẩn hóa IPv6 loopback (::1 và 0:0:0:0:0:0:0:1) thành 127.0.0.1
        MockHttpServletRequest req3 = new MockHttpServletRequest();
        req3.addHeader("X-Forwarded-For", "::1");
        assertEquals("127.0.0.1", PaymentController.resolveClientIp(req3));

        MockHttpServletRequest req4 = new MockHttpServletRequest();
        req4.setRemoteAddr("0:0:0:0:0:0:0:1");
        assertEquals("127.0.0.1", PaymentController.resolveClientIp(req4));

        // 4. Request null fallback về 127.0.0.1
        assertEquals("127.0.0.1", PaymentController.resolveClientIp(null));

        // 5. Kiểm tra Client IP được nhúng vào VNPay Checkout URL
        CreatePaymentRequest depReq = new CreatePaymentRequest();
        depReq.setAmountUsd(new BigDecimal("100.00"));
        depReq.setClientRequestId("dep_client_ip_checkout_test");

        PaymentOrderResponseDto depDto = paymentService.createDeposit(testUser.getId(), depReq, "https://api.fnmf.com", "203.0.113.195");
        assertNotNull(depDto.getCheckoutUrl());
        assertTrue(depDto.getCheckoutUrl().contains("vnp_IpAddr=203.0.113.195"), "Checkout URL phải chứa vnp_IpAddr khớp với clientIp");
    }

    @Test
    @DisplayName("21. Thiếu credentials VNPay không để lại đơn hàng PENDING mồ côi trong database")
    void test21_missingCredentialsDoesNotLeaveOrderPending() {
        String savedTmn = vnPayConfig.getTmnCode();
        String savedSecret = vnPayConfig.getHashSecret();

        try {
            vnPayConfig.setTmnCode("");
            vnPayConfig.setHashSecret("");

            CreatePaymentRequest req = new CreatePaymentRequest();
            req.setAmountUsd(new BigDecimal("80.00"));
            req.setClientRequestId("dep_missing_credentials_orphan_check");

            assertThrows(IllegalStateException.class, () -> {
                paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
            });

            long pendingCount = paymentOrderRepository.findAll().stream()
                    .filter(o -> o.getStatus() == PaymentStatus.PENDING)
                    .count();
            assertEquals(0, pendingCount, "Không được tồn tại bất kỳ đơn PENDING mồ côi nào khi thiếu credentials");

        } finally {
            vnPayConfig.setTmnCode(savedTmn);
            vnPayConfig.setHashSecret(savedSecret);
        }
    }

    @Test
    @DisplayName("22. Trang Return VNPay tuân thủ CSP style-src 'self' và không chứa CSS inline ở tất cả trạng thái")
    void test22_vnpayReturnHtmlCompliesWithCspAndContainsNoInlineStyle() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setAmountUsd(new BigDecimal("50.00"));
        req.setClientRequestId("dep_csp_all_states_check");

        PaymentOrderResponseDto dto = paymentService.createDeposit(testUser.getId(), req, "https://api.fnmf.com");
        PaymentOrder order = paymentOrderRepository.findById(dto.getPaymentOrderId()).orElseThrow();

        // 1. Trạng thái PENDING
        Map<String, String> pendingParams = buildSignedIpnParams(order, "00", "VNP_CSP_PENDING");
        String pendingHtml = vnPayPaymentController.handleVnPayReturn(pendingParams).getBody();
        assertNotNull(pendingHtml);
        assertFalse(pendingHtml.contains("<style"), "Trạng thái PENDING không được có <style> inline");
        assertFalse(pendingHtml.contains("style="), "Trạng thái PENDING không được có style attribute");
        assertTrue(pendingHtml.contains("/vnpay-return.css"));
        assertTrue(pendingHtml.contains("status-pending"));

        // 2. Trạng thái SUCCEEDED (sau khi có IPN)
        vnPayPaymentController.handleVnPayIpn(pendingParams);
        String successHtml = vnPayPaymentController.handleVnPayReturn(pendingParams).getBody();
        assertNotNull(successHtml);
        assertFalse(successHtml.contains("<style"), "Trạng thái SUCCEEDED không được có <style> inline");
        assertFalse(successHtml.contains("style="), "Trạng thái SUCCEEDED không được có style attribute");
        assertTrue(successHtml.contains("/vnpay-return.css"));
        assertTrue(successHtml.contains("status-success"));

        // 3. Trạng thái FAILED
        Map<String, String> failParams = new HashMap<>(pendingParams);
        failParams.put("vnp_SecureHash", "INVALID_HASH");
        String failedHtml = vnPayPaymentController.handleVnPayReturn(failParams).getBody();
        assertNotNull(failedHtml);
        assertFalse(failedHtml.contains("<style"), "Trạng thái FAILED không được có <style> inline");
        assertFalse(failedHtml.contains("style="), "Trạng thái FAILED không được có style attribute");
        assertTrue(failedHtml.contains("/vnpay-return.css"));
        assertTrue(failedHtml.contains("status-failed"));
    }

    @Test
    @DisplayName("23. resolveClientIp thẩm định IP nghiêm ngặt, chặn header injection và octal traversal")
    void test23_resolveClientIpStrictValidation() {
        // Injection payload
        MockHttpServletRequest reqAttack = new MockHttpServletRequest();
        reqAttack.addHeader("X-Forwarded-For", "127.0.0.1; DROP TABLE users;");
        assertEquals("127.0.0.1", PaymentController.resolveClientIp(reqAttack), "Payload SQL/Script injection phải bị từ chối");

        // Out-of-range octet
        MockHttpServletRequest reqInvalid = new MockHttpServletRequest();
        reqInvalid.addHeader("X-Forwarded-For", "999.999.999.999");
        assertEquals("127.0.0.1", PaymentController.resolveClientIp(reqInvalid), "IP ngoài dải 0-255 phải bị từ chối");

        // Leading zero / octal
        MockHttpServletRequest reqOctal = new MockHttpServletRequest();
        reqOctal.addHeader("X-Forwarded-For", "010.001.002.003");
        assertEquals("127.0.0.1", PaymentController.resolveClientIp(reqOctal), "Octal notation có số 0 đầu phải bị từ chối");

        // Fallback sang X-Real-IP nếu X-Forwarded-For không hợp lệ
        MockHttpServletRequest reqFallback = new MockHttpServletRequest();
        reqFallback.addHeader("X-Forwarded-For", "invalid_header");
        reqFallback.addHeader("X-Real-IP", "198.51.100.77");
        assertEquals("198.51.100.77", PaymentController.resolveClientIp(reqFallback));

        // Framework remoteAddr (chuẩn hóa bởi forward-headers-strategy=framework từ proxy tin cậy)
        MockHttpServletRequest reqFramework = new MockHttpServletRequest();
        reqFramework.setRemoteAddr("203.0.113.88");
        assertEquals("203.0.113.88", PaymentController.resolveClientIp(reqFramework), "Framework remoteAddr được ưu tiên");
    }

    @Test
    @DisplayName("24. Lỗi tạo URL thanh toán sau khi đã persist đơn trả HTTP 502 BAD_GATEWAY, đánh dấu FAILED trong DB và replay không chứa URL")
    void test24_checkoutUrlFailureAfterPersistMarksOrderFailedAndReplayHasNoUrl() throws Exception {
        PaymentProvider originalProvider = providerRegistry.getProvider("VNPAY_SANDBOX");

        // Tạo một Provider giả lập ném ngoại lệ khi buildCheckoutUrl sau khi order đã được persist
        PaymentProvider failingProvider = new PaymentProvider() {
            @Override
            public String getProviderName() {
                return "VNPAY_SANDBOX";
            }

            @Override
            public String buildCheckoutUrl(PaymentOrder order, String baseUrl) {
                throw new IllegalStateException("Simulated VNPay Gateway network timeout during URL generation");
            }

            @Override
            public String buildCheckoutUrl(PaymentOrder order, String baseUrl, String clientIp) {
                throw new IllegalStateException("Simulated VNPay Gateway network timeout during URL generation");
            }

            @Override
            public boolean isSandbox() {
                return true;
            }
        };

        try {
            // Đăng ký provider lỗi
            providerRegistry.registerProvider(failingProvider);

            CreatePaymentRequest req = new CreatePaymentRequest();
            req.setAmountUsd(new BigDecimal("95.00"));
            req.setClientRequestId("dep_url_build_fail_after_persist_check");

            String token = jwtUtil.generateToken(testUser.getEmail(), testUser.getId());

            // 1. Gọi HTTP POST /api/payments/deposits qua MockMvc:
            // Bắt buộc trả HTTP 502 Bad Gateway với mã lỗi an toàn PAYMENT_GATEWAY_UNAVAILABLE
            mockMvc.perform(post("/api/payments/deposits")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value("ERROR"))
                    .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error").value("Cổng thanh toán tạm thời không khả dụng. Vui lòng thử lại sau."));

            // 2. Kiểm tra database:
            // - Đã có đúng 1 đơn hàng được tạo với clientRequestId tương ứng
            PaymentOrder failedOrder = paymentOrderRepository.findByUserIdAndClientRequestId(testUser.getId(), req.getClientRequestId())
                    .orElseThrow(() -> new AssertionError("Đơn hàng phải được persist vào DB trước khi buildCheckoutUrl lỗi"));

            // - Trạng thái đơn trong DB PHẢI LÀ FAILED, tuyệt đối không được là PENDING
            assertEquals(PaymentStatus.FAILED, failedOrder.getStatus(), "Đơn hàng phải chuyển sang FAILED khi build URL lỗi");
            assertNotNull(failedOrder.getFailureReason());
            assertTrue(failedOrder.getFailureReason().contains("Khởi tạo URL thanh toán thất bại"));

            // - Xác nhận KHÔNG có bất kỳ đơn PENDING nào trong toàn bộ DB
            long pendingCount = paymentOrderRepository.findAll().stream()
                    .filter(o -> o.getStatus() == PaymentStatus.PENDING)
                    .count();
            assertEquals(0, pendingCount, "DB không được tồn tại bất kỳ đơn PENDING mồ côi nào");

            // 3. Khôi phục lại provider hoạt động bình thường
            providerRegistry.registerProvider(originalProvider);

            // 4. Người dùng RETRY với cùng clientRequestId qua HTTP POST:
            // Hệ thống thực hiện Idempotent Replay đơn cũ, trả HTTP 200 OK
            mockMvc.perform(post("/api/payments/deposits")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.checkoutUrl").doesNotExist())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("thất bại")));

            // - Số dư ví không hề bị thay đổi
            Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
            assertBigDecimalEquals(new BigDecimal("1000.00"), wallet.getBalanceUsd());

        } finally {
            providerRegistry.registerProvider(originalProvider);
        }
    }
}
