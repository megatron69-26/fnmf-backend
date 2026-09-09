package com.llmgateway.service;

import com.llmgateway.dto.payment.CreatePaymentRequest;
import com.llmgateway.dto.payment.PaymentOrderResponseDto;
import com.llmgateway.entity.LedgerEntryType;
import com.llmgateway.entity.PaymentEvent;
import com.llmgateway.entity.PaymentOrder;
import com.llmgateway.entity.PaymentStatus;
import com.llmgateway.entity.PaymentType;
import com.llmgateway.entity.Wallet;
import com.llmgateway.entity.WalletLedger;
import com.llmgateway.exception.CheckoutExpiredException;
import com.llmgateway.exception.IdempotencyConflictException;
import com.llmgateway.exception.InsufficientBalanceException;
import com.llmgateway.repository.PaymentEventRepository;
import com.llmgateway.repository.PaymentOrderRepository;
import com.llmgateway.repository.WalletLedgerRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.service.payment.PaymentProvider;
import com.llmgateway.service.payment.PaymentProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final BigDecimal MAX_SANDBOX_AMOUNT = new BigDecimal("100000.00");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    private final PaymentOrderRepository paymentOrderRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final WalletRepository walletRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentOrderExecutor paymentOrderExecutor;
    private final PaymentEventExecutor paymentEventExecutor;

    @Autowired
    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry,
                          PaymentOrderExecutor paymentOrderExecutor,
                          PaymentEventExecutor paymentEventExecutor) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.walletRepository = walletRepository;
        this.providerRegistry = providerRegistry;
        this.paymentOrderExecutor = paymentOrderExecutor;
        this.paymentEventExecutor = paymentEventExecutor;
    }

    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry,
                          PaymentOrderExecutor paymentOrderExecutor) {
        this(paymentOrderRepository, walletLedgerRepository, paymentEventRepository, walletRepository, providerRegistry,
                paymentOrderExecutor, new PaymentEventExecutor(paymentEventRepository));
    }

    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry) {
        this(paymentOrderRepository, walletLedgerRepository, paymentEventRepository, walletRepository, providerRegistry,
                new PaymentOrderExecutor(paymentOrderRepository),
                new PaymentEventExecutor(paymentEventRepository));
    }

    private BigDecimal validateAndNormalizeAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw new IllegalArgumentException("Số tiền USD không được để trống");
        }
        if (rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền nạp/rút phải lớn hơn 0");
        }
        if (rawAmount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Số tiền USD tối đa 2 chữ số thập phân (cents)");
        }
        if (rawAmount.compareTo(MAX_SANDBOX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Số tiền giao dịch Sandbox tối đa $" + MAX_SANDBOX_AMOUNT + " USD");
        }
        return rawAmount.setScale(2, RoundingMode.HALF_UP);
    }

    public PaymentOrderResponseDto createDeposit(Long userId, CreatePaymentRequest request, String baseUrl) {
        BigDecimal amount = validateAndNormalizeAmount(request.getAmountUsd());
        String clientRequestId = request.getClientRequestId().trim();

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa có ví vốn ảo"));

        // 1. Fast-check Idempotency: Replay nếu cùng request, conflict nếu khác payload
        Optional<PaymentOrder> existingOpt = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
        if (existingOpt.isPresent()) {
            return handleExistingOrder(existingOpt.get(), PaymentType.DEPOSIT, amount, clientRequestId, baseUrl);
        }

        String checkoutToken = generateSecureToken();
        PaymentProvider provider = providerRegistry.getDefaultProvider();

        try {
            PaymentOrder order = paymentOrderExecutor.createAndPersistOrder(
                    userId,
                    wallet.getId(),
                    clientRequestId,
                    provider.getProviderName(),
                    PaymentType.DEPOSIT,
                    amount,
                    checkoutToken
            );
            log.info("CREATED DEPOSIT ORDER | orderId={} | userId={} | amount={} | checkoutToken={}",
                    order.getId(), userId, amount, maskToken(checkoutToken));
            return toResponseDto(order, baseUrl, "Tạo yêu cầu nạp tiền Sandbox thành công!");
        } catch (DataIntegrityViolationException dive) {
            // Race condition: luồng đồng thời khác đã tạo đơn với cùng clientRequestId trong tích tắc
            log.info("CONCURRENT DEPOSIT CREATION RACE DETECTED | userId={} | clientRequestId={}", userId, clientRequestId);
            PaymentOrder raced = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId)
                    .orElseThrow(() -> dive);
            return handleExistingOrder(raced, PaymentType.DEPOSIT, amount, clientRequestId, baseUrl);
        }
    }

    public PaymentOrderResponseDto createWithdrawal(Long userId, CreatePaymentRequest request, String baseUrl) {
        BigDecimal amount = validateAndNormalizeAmount(request.getAmountUsd());
        String clientRequestId = request.getClientRequestId().trim();

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa có ví vốn ảo"));

        // 1. Fast-check Idempotency: Replay nếu cùng request, conflict nếu khác payload
        Optional<PaymentOrder> existingOpt = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
        if (existingOpt.isPresent()) {
            return handleExistingOrder(existingOpt.get(), PaymentType.WITHDRAWAL, amount, clientRequestId, baseUrl);
        }

        // Kiểm tra số dư khả dụng
        if (wallet.getBalanceUsd().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Số dư khả dụng không đủ (" + wallet.getBalanceUsd() + " USD) để rút " + amount + " USD");
        }

        String checkoutToken = generateSecureToken();
        PaymentProvider provider = providerRegistry.getDefaultProvider();

        try {
            PaymentOrder order = paymentOrderExecutor.createAndPersistOrder(
                    userId,
                    wallet.getId(),
                    clientRequestId,
                    provider.getProviderName(),
                    PaymentType.WITHDRAWAL,
                    amount,
                    checkoutToken
            );
            log.info("CREATED WITHDRAWAL ORDER | orderId={} | userId={} | amount={} | checkoutToken={}",
                    order.getId(), userId, amount, maskToken(checkoutToken));
            return toResponseDto(order, baseUrl, "Tạo yêu cầu rút tiền Sandbox thành công!");
        } catch (DataIntegrityViolationException dive) {
            log.info("CONCURRENT WITHDRAWAL CREATION RACE DETECTED | userId={} | clientRequestId={}", userId, clientRequestId);
            PaymentOrder raced = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId)
                    .orElseThrow(() -> dive);
            return handleExistingOrder(raced, PaymentType.WITHDRAWAL, amount, clientRequestId, baseUrl);
        }
    }

    private PaymentOrderResponseDto handleExistingOrder(PaymentOrder existing, PaymentType expectedType,
                                                        BigDecimal expectedAmount, String clientRequestId,
                                                        String baseUrl) {
        if (existing.getType() == expectedType && existing.getAmountUsd().compareTo(expectedAmount) == 0) {
            log.info("REPLAYING {} ORDER | userId={} | clientRequestId={} | orderId={}",
                    existing.getType(), existing.getUserId(), clientRequestId, existing.getId());
            String msg = (existing.getType() == PaymentType.DEPOSIT ? "Lệnh nạp tiền" : "Lệnh rút tiền")
                    + " đã tồn tại, trả lại kết quả (Idempotent Replay)";
            return toResponseDto(existing, baseUrl, msg);
        } else {
            throw new IdempotencyConflictException("clientRequestId '" + clientRequestId + "' đã được sử dụng với loại hoặc số tiền khác!");
        }
    }

    private PaymentOrder checkAndExpireOrder(PaymentOrder order) {
        if (order.getExpiresAt() != null && LocalDateTime.now().isAfter(order.getExpiresAt())) {
            if (order.getStatus() == PaymentStatus.PENDING || order.getStatus() == PaymentStatus.PROCESSING) {
                order.setStatus(PaymentStatus.FAILED);
                order.setFailureReason("Phiên thanh toán đã hết hạn sau 15 phút (Token expired)");
                order.setCompletedAt(LocalDateTime.now());
                order.setUpdatedAt(LocalDateTime.now());
                order = paymentOrderRepository.save(order);
                recordPaymentEvent(order.getProvider(), "EXPIRE_" + order.getId(), order.getId(), "ORDER_EXPIRED", "Token expired after 15 minutes");
            }
        }
        return order;
    }

    @Transactional
    public PaymentOrderResponseDto getPaymentOrder(Long userId, Long orderId, String baseUrl) {
        PaymentOrder order = paymentOrderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giao dịch với ID: " + orderId));
        order = checkAndExpireOrder(order);
        return toResponseDto(order, baseUrl, "Thông tin giao dịch");
    }

    @Transactional
    public List<PaymentOrderResponseDto> getUserPaymentOrders(Long userId, String baseUrl) {
        List<PaymentOrder> orders = paymentOrderRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        return orders.stream()
                .map(this::checkAndExpireOrder)
                .map(o -> toResponseDto(o, baseUrl, null))
                .collect(Collectors.toList());
    }

    @Transactional
    public PaymentOrderResponseDto cancelPaymentOrder(Long userId, Long orderId, String baseUrl) {
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giao dịch để hủy"));

        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Không tìm thấy giao dịch để hủy");
        }

        order = checkAndExpireOrder(order);

        if (order.getStatus() == PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException("Không thể hủy giao dịch đã hoàn tất thành công");
        }
        if (order.getStatus() == PaymentStatus.CANCELLED || order.getStatus() == PaymentStatus.FAILED) {
            return toResponseDto(order, baseUrl, "Giao dịch đã ở trạng thái kết thúc: " + order.getStatus());
        }

        order.setStatus(PaymentStatus.CANCELLED);
        order.setFailureReason("Người dùng chủ động hủy giao dịch");
        order.setCompletedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        paymentOrderRepository.save(order);

        recordPaymentEvent(order.getProvider(), "CANCEL_" + order.getId(), order.getId(), "ORDER_CANCELLED", "User cancelled order");

        return toResponseDto(order, baseUrl, "Hủy giao dịch thành công");
    }

    @Transactional(noRollbackFor = CheckoutExpiredException.class)
    public PaymentOrder getOrderByCheckoutToken(String checkoutToken) {
        PaymentOrder order = paymentOrderRepository.findByCheckoutToken(checkoutToken)
                .orElseThrow(() -> new IllegalArgumentException("Mã giao dịch checkout không hợp lệ"));
        order = checkAndExpireOrder(order);
        if (order.getStatus() == PaymentStatus.FAILED && "Phiên thanh toán đã hết hạn sau 15 phút (Token expired)".equals(order.getFailureReason())) {
            throw new CheckoutExpiredException("Phiên thanh toán đã hết hạn sau 15 phút (Token expired)");
        }
        return order;
    }

    /**
     * Xử lý xác nhận / mô phỏng kết quả thanh toán từ Hosted Checkout Sandbox.
     * Pessimistic lock bảo đảm hai request đồng thời cùng token chỉ thay đổi số dư đúng một lần duy nhất.
     */
    @Transactional(noRollbackFor = CheckoutExpiredException.class)
    public PaymentOrder processCheckoutAction(String checkoutToken, String action, String failureReason) {
        if (checkoutToken == null || checkoutToken.isBlank()) {
            throw new IllegalArgumentException("Token checkout không được để trống");
        }
        String cleanAction = (action != null) ? action.trim().toUpperCase() : "SUCCESS";

        PaymentOrder order = paymentOrderRepository.findByCheckoutTokenForUpdate(checkoutToken.trim())
                .orElseThrow(() -> new IllegalArgumentException("Giao dịch checkout không tồn tại"));

        // Kiểm tra token hết hạn 15 phút -> chuyển trạng thái FAILED và trả HTTP 410 GONE
        if (order.getExpiresAt() != null && LocalDateTime.now().isAfter(order.getExpiresAt())) {
            if (order.getStatus() == PaymentStatus.PENDING || order.getStatus() == PaymentStatus.PROCESSING) {
                order.setStatus(PaymentStatus.FAILED);
                order.setFailureReason("Phiên thanh toán đã hết hạn sau 15 phút (Token expired)");
                order.setCompletedAt(LocalDateTime.now());
                order.setUpdatedAt(LocalDateTime.now());
                paymentOrderRepository.save(order);
                recordPaymentEvent(order.getProvider(), "EXPIRE_" + order.getId(), order.getId(), "ORDER_EXPIRED", "Token expired after 15 minutes");
            }
            throw new CheckoutExpiredException("Phiên thanh toán đã hết hạn sau 15 phút (HTTP 410 GONE)");
        }

        // Nếu đã hoàn tất trước đó -> Replay idempotent, tuyệt đối không cộng/trừ lần 2
        if (order.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("REPLAYING SUCCEEDED CHECKOUT | orderId={} | status=SUCCEEDED", order.getId());
            return order;
        }
        if (order.getStatus() == PaymentStatus.CANCELLED || order.getStatus() == PaymentStatus.FAILED) {
            log.info("CHECKOUT ALREADY TERMINATED | orderId={} | status={}", order.getId(), order.getStatus());
            return order;
        }

        if ("SUCCESS".equals(cleanAction)) {
            // Khóa bi quan (Pessimistic Lock) ví tiền
            Wallet wallet = walletRepository.findByIdForUpdate(order.getWalletId())
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy ví tiền gắn với order: " + order.getWalletId()));

            // Kiểm tra chống double-credit qua bảng sổ cái
            if (walletLedgerRepository.existsByPaymentOrderId(order.getId())) {
                log.warn("LEDGER DUPLICATE GUARD TRIGGERED | orderId={}", order.getId());
                return order;
            }

            BigDecimal before = wallet.getBalanceUsd();

            if (order.getType() == PaymentType.DEPOSIT) {
                wallet.addFunds(order.getAmountUsd());
                walletRepository.save(wallet);
                BigDecimal after = wallet.getBalanceUsd();

                // Ghi sổ cái (Ledger) chính xác 1 lần
                WalletLedger ledger = new WalletLedger(
                        wallet.getId(),
                        order.getId(),
                        LedgerEntryType.DEPOSIT,
                        order.getAmountUsd(),
                        before,
                        after,
                        "Sandbox Deposit via " + order.getProvider()
                );
                walletLedgerRepository.save(ledger);

                recordPaymentEvent(order.getProvider(), "DEP_SUCC_" + order.getId(), order.getId(), "PAYMENT_SUCCEEDED", "Amount: " + order.getAmountUsd());
                log.info("PAYMENT SUCCEEDED (DEPOSIT) | orderId={} | walletId={} | before={} | after={}",
                        order.getId(), wallet.getId(), before, after);

            } else if (order.getType() == PaymentType.WITHDRAWAL) {
                if (wallet.getBalanceUsd().compareTo(order.getAmountUsd()) < 0) {
                    order.setStatus(PaymentStatus.FAILED);
                    order.setFailureReason("Số dư không đủ tại thời điểm rút tiền");
                    order.setCompletedAt(LocalDateTime.now());
                    order.setUpdatedAt(LocalDateTime.now());
                    paymentOrderRepository.save(order);
                    recordPaymentEvent(order.getProvider(), "WDL_FAIL_" + order.getId(), order.getId(), "PAYMENT_FAILED", "Insufficient balance");
                    return order;
                }

                wallet.deductFunds(order.getAmountUsd());
                walletRepository.save(wallet);
                BigDecimal after = wallet.getBalanceUsd();

                // Ghi sổ cái (Ledger) với số âm cho rút tiền
                WalletLedger ledger = new WalletLedger(
                        wallet.getId(),
                        order.getId(),
                        LedgerEntryType.WITHDRAWAL,
                        order.getAmountUsd().negate(),
                        before,
                        after,
                        "Sandbox Withdrawal via " + order.getProvider()
                );
                walletLedgerRepository.save(ledger);

                recordPaymentEvent(order.getProvider(), "WDL_SUCC_" + order.getId(), order.getId(), "PAYMENT_SUCCEEDED", "Amount: " + order.getAmountUsd());
                log.info("PAYMENT SUCCEEDED (WITHDRAWAL) | orderId={} | walletId={} | before={} | after={}",
                        order.getId(), wallet.getId(), before, after);
            }

            order.setStatus(PaymentStatus.SUCCEEDED);
            order.setCompletedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            return paymentOrderRepository.save(order);

        } else if ("FAIL".equals(cleanAction)) {
            order.setStatus(PaymentStatus.FAILED);
            order.setFailureReason((failureReason != null && !failureReason.isBlank()) ? failureReason.trim() : "Giao dịch bị mô phỏng thất bại bởi người dùng");
            order.setCompletedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            recordPaymentEvent(order.getProvider(), "USER_FAIL_" + order.getId(), order.getId(), "PAYMENT_FAILED", order.getFailureReason());
            return paymentOrderRepository.save(order);

        } else if ("CANCEL".equals(cleanAction)) {
            order.setStatus(PaymentStatus.CANCELLED);
            order.setFailureReason("Người dùng hủy giao dịch trên giao diện thanh toán");
            order.setCompletedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            recordPaymentEvent(order.getProvider(), "USER_CANCEL_" + order.getId(), order.getId(), "PAYMENT_CANCELLED", order.getFailureReason());
            return paymentOrderRepository.save(order);

        } else {
            throw new IllegalArgumentException("Hành động không hợp lệ: " + action);
        }
    }

    private void recordPaymentEvent(String provider, String providerEventId, Long orderId, String eventType, String payload) {
        try {
            String hash = sha256(payload != null ? payload : "");
            PaymentEvent event = new PaymentEvent(provider, providerEventId, orderId, eventType, hash);
            paymentEventExecutor.recordEvent(event);
        } catch (Exception e) {
            log.warn("Không thể ghi nhận payment_event (bỏ qua để không chặn luồng chính): {}", e.getMessage());
        }
    }

    private PaymentOrderResponseDto toResponseDto(PaymentOrder order, String baseUrl, String message) {
        PaymentOrderResponseDto dto = new PaymentOrderResponseDto();
        dto.setPaymentOrderId(order.getId());
        dto.setType(order.getType().name());
        dto.setAmountUsd(order.getAmountUsd());
        dto.setStatus(order.getStatus().name());
        dto.setProvider(order.getProvider());
        dto.setFailureReason(order.getFailureReason());
        dto.setMessage(message);

        if (order.getCreatedAt() != null) {
            dto.setCreatedAt(order.getCreatedAt().format(ISO_FORMATTER));
        }
        if (order.getCompletedAt() != null) {
            dto.setCompletedAt(order.getCompletedAt().format(ISO_FORMATTER));
        }

        if (baseUrl != null && !baseUrl.isBlank() && order.getCheckoutToken() != null) {
            String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            dto.setCheckoutUrl(cleanBase + "/sandbox-bank/checkout/" + order.getCheckoutToken());
        }

        return dto;
    }

    private String generateSecureToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }
}