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
import com.llmgateway.exception.PaymentGatewayUnavailableException;
import com.llmgateway.repository.PaymentEventRepository;
import com.llmgateway.repository.PaymentOrderRepository;
import com.llmgateway.repository.WalletLedgerRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.config.VnPayConfig;
import com.llmgateway.service.payment.PaymentProvider;
import com.llmgateway.service.payment.PaymentProviderRegistry;
import com.llmgateway.util.VnPayUtil;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final VnPayConfig vnPayConfig;

    @Autowired
    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry,
                          PaymentOrderExecutor paymentOrderExecutor,
                          PaymentEventExecutor paymentEventExecutor,
                          @Autowired(required = false) VnPayConfig vnPayConfig) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.walletRepository = walletRepository;
        this.providerRegistry = providerRegistry;
        this.paymentOrderExecutor = paymentOrderExecutor;
        this.paymentEventExecutor = paymentEventExecutor;
        this.vnPayConfig = (vnPayConfig != null) ? vnPayConfig : new VnPayConfig();
    }

    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry,
                          PaymentOrderExecutor paymentOrderExecutor,
                          PaymentEventExecutor paymentEventExecutor) {
        this(paymentOrderRepository, walletLedgerRepository, paymentEventRepository, walletRepository, providerRegistry,
                paymentOrderExecutor, paymentEventExecutor, new VnPayConfig());
    }

    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry,
                          PaymentOrderExecutor paymentOrderExecutor) {
        this(paymentOrderRepository, walletLedgerRepository, paymentEventRepository, walletRepository, providerRegistry,
                paymentOrderExecutor, new PaymentEventExecutor(paymentEventRepository), new VnPayConfig());
    }

    public PaymentService(PaymentOrderRepository paymentOrderRepository,
                          WalletLedgerRepository walletLedgerRepository,
                          PaymentEventRepository paymentEventRepository,
                          WalletRepository walletRepository,
                          PaymentProviderRegistry providerRegistry) {
        this(paymentOrderRepository, walletLedgerRepository, paymentEventRepository, walletRepository, providerRegistry,
                new PaymentOrderExecutor(paymentOrderRepository),
                new PaymentEventExecutor(paymentEventRepository),
                new VnPayConfig());
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
        return createDeposit(userId, request, baseUrl, null);
    }

    public PaymentOrderResponseDto createDeposit(Long userId, CreatePaymentRequest request, String baseUrl, String clientIp) {
        BigDecimal amount = validateAndNormalizeAmount(request.getAmountUsd());
        String clientRequestId = request.getClientRequestId().trim();

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa có ví vốn ảo"));

        // 1. Fast-check Idempotency: Replay nếu cùng request, conflict nếu khác payload
        Optional<PaymentOrder> existingOpt = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
        if (existingOpt.isPresent()) {
            return handleExistingOrder(existingOpt.get(), PaymentType.DEPOSIT, amount, clientRequestId, baseUrl, clientIp);
        }

        String checkoutToken = generateSecureToken();
        PaymentProvider provider = providerRegistry.getConfiguredProvider();

        // Kiểm tra trước cấu hình provider nếu là VNPay để tránh tạo đơn mồ côi
        if (PaymentProviderRegistry.PROVIDER_VNPAY.equalsIgnoreCase(provider.getProviderName()) && (vnPayConfig == null || !vnPayConfig.isConfigured())) {
            throw new IllegalStateException("Cấu hình VNPay Sandbox (TMN_CODE hoặc HASH_SECRET) chưa được thiết lập!");
        }

        BigDecimal exchangeRateSnapshot = (vnPayConfig != null) ? vnPayConfig.getExchangeRate() : new BigDecimal("25000");
        BigDecimal amountVnd = amount.multiply(exchangeRateSnapshot).setScale(0, RoundingMode.HALF_UP);

        PaymentOrder order = null;
        try {
            order = paymentOrderExecutor.createAndPersistOrder(
                    userId,
                    wallet.getId(),
                    clientRequestId,
                    provider.getProviderName(),
                    PaymentType.DEPOSIT,
                    amount,
                    checkoutToken,
                    amountVnd,
                    exchangeRateSnapshot
            );
            log.info("CREATED DEPOSIT ORDER | orderId={} | userId={} | amountUsd={} | amountVnd={} | provider={} | checkoutToken={}",
                    order.getId(), userId, amount, amountVnd, provider.getProviderName(), maskToken(checkoutToken));
            return toResponseDto(order, baseUrl, clientIp, "Tạo yêu cầu nạp tiền Sandbox thành công!");
        } catch (DataIntegrityViolationException dive) {
            // Race condition: luồng đồng thời khác đã tạo đơn với cùng clientRequestId trong tích tắc
            log.info("CONCURRENT DEPOSIT CREATION RACE DETECTED | userId={} | clientRequestId={}", userId, clientRequestId);
            PaymentOrder raced = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId)
                    .orElseThrow(() -> dive);
            return handleExistingOrder(raced, PaymentType.DEPOSIT, amount, clientRequestId, baseUrl, clientIp);
        } catch (Exception ex) {
            if (order != null && order.getId() != null) {
                log.error("Khởi tạo checkout URL thất bại cho đơn {}. Đánh dấu FAILED để tránh đơn mồ côi: {}",
                        order.getId(), ex.getMessage());
                try {
                    order.setStatus(PaymentStatus.FAILED);
                    order.setFailureReason("Khởi tạo URL thanh toán thất bại: " + ex.getMessage());
                    order.setCompletedAt(LocalDateTime.now());
                    order.setUpdatedAt(LocalDateTime.now());
                    paymentOrderRepository.save(order);
                } catch (Exception saveEx) {
                    log.error("Không thể cập nhật trạng thái FAILED cho đơn {}: {}", order.getId(), saveEx.getMessage());
                }
                throw new PaymentGatewayUnavailableException("Không thể tạo liên kết thanh toán từ cổng " + provider.getProviderName() + ": " + ex.getMessage(), ex);
            }
            throw ex;
        }
    }

    public PaymentOrderResponseDto createWithdrawal(Long userId, CreatePaymentRequest request, String baseUrl) {
        return createWithdrawal(userId, request, baseUrl, null);
    }

    public PaymentOrderResponseDto createWithdrawal(Long userId, CreatePaymentRequest request, String baseUrl, String clientIp) {
        BigDecimal amount = validateAndNormalizeAmount(request.getAmountUsd());
        String clientRequestId = request.getClientRequestId().trim();

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa có ví vốn ảo"));

        // 1. Fast-check Idempotency: Replay nếu cùng request, conflict nếu khác payload
        Optional<PaymentOrder> existingOpt = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
        if (existingOpt.isPresent()) {
            return handleExistingOrder(existingOpt.get(), PaymentType.WITHDRAWAL, amount, clientRequestId, baseUrl, clientIp);
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
            return toResponseDto(order, baseUrl, clientIp, "Tạo yêu cầu rút tiền Sandbox thành công!");
        } catch (DataIntegrityViolationException dive) {
            log.info("CONCURRENT WITHDRAWAL CREATION RACE DETECTED | userId={} | clientRequestId={}", userId, clientRequestId);
            PaymentOrder raced = paymentOrderRepository.findByUserIdAndClientRequestId(userId, clientRequestId)
                    .orElseThrow(() -> dive);
            return handleExistingOrder(raced, PaymentType.WITHDRAWAL, amount, clientRequestId, baseUrl, clientIp);
        }
    }

    private PaymentOrderResponseDto handleExistingOrder(PaymentOrder existing, PaymentType expectedType,
                                                        BigDecimal expectedAmount, String clientRequestId,
                                                        String baseUrl, String clientIp) {
        if (existing.getType() == expectedType && existing.getAmountUsd().compareTo(expectedAmount) == 0) {
            log.info("REPLAYING {} ORDER | userId={} | clientRequestId={} | orderId={} | status={}",
                    existing.getType(), existing.getUserId(), clientRequestId, existing.getId(), existing.getStatus());
            String msg;
            if (existing.getStatus() == PaymentStatus.FAILED) {
                msg = "Đơn hàng trước đó đã thất bại: " + (existing.getFailureReason() != null ? existing.getFailureReason() : "Lỗi xử lý");
            } else if (existing.getStatus() == PaymentStatus.CANCELLED) {
                msg = "Đơn hàng trước đó đã bị hủy";
            } else if (existing.getStatus() == PaymentStatus.SUCCEEDED) {
                msg = "Đơn hàng trước đó đã hoàn tất thành công";
            } else {
                msg = (existing.getType() == PaymentType.DEPOSIT ? "Lệnh nạp tiền" : "Lệnh rút tiền")
                        + " đã tồn tại, trả lại kết quả (Idempotent Replay)";
            }
            return toResponseDto(existing, baseUrl, clientIp, msg);
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
        if (!PaymentProviderRegistry.PROVIDER_INTERNAL.equalsIgnoreCase(order.getProvider())) {
            throw new IllegalArgumentException("Cổng nội bộ chỉ áp dụng cho đơn SANDBOX_INTERNAL. Đơn này thuộc provider: " + order.getProvider());
        }
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

        if (!PaymentProviderRegistry.PROVIDER_INTERNAL.equalsIgnoreCase(order.getProvider())) {
            throw new IllegalArgumentException("Cổng nội bộ chỉ áp dụng cho đơn SANDBOX_INTERNAL. Đơn này thuộc provider: " + order.getProvider());
        }

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
        return toResponseDto(order, baseUrl, null, message);
    }

    private PaymentOrderResponseDto toResponseDto(PaymentOrder order, String baseUrl, String clientIp, String message) {
        PaymentOrderResponseDto dto = new PaymentOrderResponseDto();
        dto.setPaymentOrderId(order.getId());
        dto.setType(order.getType().name());
        dto.setAmountUsd(order.getAmountUsd());
        dto.setAmountVnd(order.getAmountVnd());
        dto.setExchangeRateSnapshot(order.getExchangeRateSnapshot());
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

        boolean isActionable = order.getStatus() == PaymentStatus.PENDING || order.getStatus() == PaymentStatus.PROCESSING;
        if (isActionable && baseUrl != null && !baseUrl.isBlank() && order.getCheckoutToken() != null) {
            PaymentProvider provider = providerRegistry.getProvider(order.getProvider());
            try {
                dto.setCheckoutUrl(provider.buildCheckoutUrl(order, baseUrl, clientIp));
            } catch (Exception e) {
                if (PaymentProviderRegistry.PROVIDER_VNPAY.equalsIgnoreCase(order.getProvider())) {
                    log.error("Failed to build checkout URL via VNPay Sandbox: {}", e.getMessage(), e);
                    throw new IllegalStateException("Không thể khởi tạo phiên thanh toán VNPay Sandbox: " + e.getMessage(), e);
                }
                log.warn("Failed to build checkout URL via provider {}: {}", order.getProvider(), e.getMessage());
                String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                dto.setCheckoutUrl(cleanBase + "/sandbox-bank/checkout/" + order.getCheckoutToken());
            }
        } else {
            dto.setCheckoutUrl(null);
        }

        return dto;
    }

    /**
     * Xử lý thông báo kết quả thanh toán từ VNPay Server-to-Server (IPN).
     * Đây là NGUỒN SỰ THẬT DUY NHẤT để hạch toán số dư vốn mô phỏng.
     */
    @Transactional
    public Map<String, String> processVnPayIpn(Map<String, String> params) {
        Map<String, String> response = new HashMap<>();

        if (vnPayConfig == null || !vnPayConfig.isConfigured()) {
            log.error("VNPAY IPN CONFIG ERROR: VnPayConfig missing or incomplete");
            response.put("RspCode", "99");
            response.put("Message", "Unknown error");
            return response;
        }

        // 1. Xác minh HMAC SHA-512 constant-time
        if (!VnPayUtil.verifySignature(params, vnPayConfig.getHashSecret())) {
            log.warn("VNPAY IPN SIGNATURE FAILED | txnRef={} | responseCode={}",
                    params.get("vnp_TxnRef"), params.get("vnp_ResponseCode"));
            response.put("RspCode", "97");
            response.put("Message", "Invalid Checksum");
            return response;
        }

        // 2. Kiểm tra vnp_TmnCode
        String tmnCode = params.get("vnp_TmnCode");
        if (tmnCode == null || !tmnCode.equalsIgnoreCase(vnPayConfig.getTmnCode())) {
            log.warn("VNPAY IPN TMN_CODE MISMATCH | received={} | expected={}", tmnCode, vnPayConfig.getTmnCode());
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        // 3. Tìm đúng vnp_TxnRef
        String txnRef = params.get("vnp_TxnRef");
        if (txnRef == null || txnRef.isBlank()) {
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        PaymentOrder order = null;
        try {
            Long orderId = Long.parseLong(txnRef.trim());
            order = paymentOrderRepository.findByIdForUpdate(orderId).orElse(null);
        } catch (NumberFormatException e) {
            order = paymentOrderRepository.findByCheckoutTokenForUpdate(txnRef.trim()).orElse(null);
        }
        if (order == null) {
            order = paymentOrderRepository.findByCheckoutTokenForUpdate(txnRef.trim()).orElse(null);
        }
        if (order == null) {
            log.warn("VNPAY IPN ORDER NOT FOUND | txnRef={}", txnRef);
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        // Kiểm tra đúng provider VNPAY_SANDBOX và loại giao dịch DEPOSIT
        if (!PaymentProviderRegistry.PROVIDER_VNPAY.equalsIgnoreCase(order.getProvider())) {
            log.warn("VNPAY IPN REJECTED: Order belongs to different provider | orderId={} | provider={}", order.getId(), order.getProvider());
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }
        if (order.getType() != PaymentType.DEPOSIT) {
            log.warn("VNPAY IPN REJECTED: VNPay only supports DEPOSIT | orderId={} | type={}", order.getId(), order.getType());
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        // 4. Kiểm tra vnp_Amount khớp đơn
        String vnpAmountStr = params.get("vnp_Amount");
        if (vnpAmountStr == null || vnpAmountStr.isBlank()) {
            response.put("RspCode", "04");
            response.put("Message", "Invalid amount");
            return response;
        }
        try {
            long receivedVnpAmount = Long.parseLong(vnpAmountStr.trim());
            BigDecimal expectedVnd = order.getAmountVnd();
            if (expectedVnd == null) {
                BigDecimal rate = (order.getExchangeRateSnapshot() != null)
                        ? order.getExchangeRateSnapshot()
                        : vnPayConfig.getExchangeRate();
                expectedVnd = order.getAmountUsd().multiply(rate).setScale(0, RoundingMode.HALF_UP);
            }
            long expectedVnpAmount = expectedVnd.multiply(new BigDecimal("100")).longValueExact();
            if (receivedVnpAmount != expectedVnpAmount) {
                log.warn("VNPAY IPN AMOUNT MISMATCH | orderId={} | received={} | expected={}",
                        order.getId(), receivedVnpAmount, expectedVnpAmount);
                response.put("RspCode", "04");
                response.put("Message", "Invalid amount");
                return response;
            }
        } catch (Exception e) {
            log.warn("VNPAY IPN AMOUNT PARSE ERROR | orderId={} | vnpAmountStr={}", order.getId(), vnpAmountStr);
            response.put("RspCode", "04");
            response.put("Message", "Invalid amount");
            return response;
        }

        // 5. Kiểm tra replay protection (idempotency)
        if (order.getStatus() == PaymentStatus.SUCCEEDED || order.getStatus() == PaymentStatus.FAILED || order.getStatus() == PaymentStatus.CANCELLED) {
            log.info("VNPAY IPN ALREADY CONFIRMED | orderId={} | status={}", order.getId(), order.getStatus());
            response.put("RspCode", "02");
            response.put("Message", "Order already confirmed");
            return response;
        }

        // 6. Kiểm tra đơn chưa hết hạn
        if (order.getExpiresAt() != null && LocalDateTime.now().isAfter(order.getExpiresAt())) {
            order.setStatus(PaymentStatus.FAILED);
            order.setFailureReason("Phiên thanh toán đã hết hạn sau 15 phút (Token expired)");
            order.setCompletedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.save(order);
            recordPaymentEvent(order.getProvider(), "VNP_EXPIRE_" + order.getId(), order.getId(), "ORDER_EXPIRED", "Token expired before IPN");
            response.put("RspCode", "02");
            response.put("Message", "Order already confirmed");
            return response;
        }

        // 7. Kiểm tra kết quả thanh toán từ VNPay
        String responseCode = params.get("vnp_ResponseCode");
        String transactionStatus = params.get("vnp_TransactionStatus");
        String transactionNo = params.get("vnp_TransactionNo");

        order.setProviderTransactionNo(transactionNo);
        order.setProviderResponseCode(responseCode);

        // Bắt buộc cả vnp_ResponseCode == "00" VÀ vnp_TransactionStatus == "00"
        boolean isSuccess = "00".equals(responseCode) && "00".equals(transactionStatus);

        if (isSuccess) {
            Long targetWalletId = order.getWalletId();
            Wallet wallet = walletRepository.findByIdForUpdate(targetWalletId)
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy ví tiền gắn với order: " + targetWalletId));

            if (walletLedgerRepository.existsByPaymentOrderId(order.getId())) {
                log.warn("VNPAY IPN LEDGER DUPLICATE GUARD TRIGGERED | orderId={}", order.getId());
                response.put("RspCode", "02");
                response.put("Message", "Order already confirmed");
                return response;
            }

            BigDecimal before = wallet.getBalanceUsd();
            wallet.addFunds(order.getAmountUsd());
            walletRepository.save(wallet);
            BigDecimal after = wallet.getBalanceUsd();

            WalletLedger ledger = new WalletLedger(
                    wallet.getId(),
                    order.getId(),
                    LedgerEntryType.DEPOSIT,
                    order.getAmountUsd(),
                    before,
                    after,
                    "VNPay Sandbox Deposit via " + order.getProvider()
            );
            walletLedgerRepository.save(ledger);

            order.setStatus(PaymentStatus.SUCCEEDED);
            order.setPaidAt(LocalDateTime.now());
            order.setCompletedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.save(order);

            recordPaymentEvent(order.getProvider(), "VNP_SUCC_" + (transactionNo != null ? transactionNo : order.getId()), order.getId(), "PAYMENT_SUCCEEDED", "VnpAmount: " + vnpAmountStr);
            log.info("VNPAY IPN DEPOSIT SUCCEEDED | orderId={} | amountUsd={} | walletId={} | before={} | after={}",
                    order.getId(), order.getAmountUsd(), wallet.getId(), before, after);

            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
            return response;
        } else {
            order.setStatus(PaymentStatus.FAILED);
            order.setFailureReason("Giao dịch VNPay không thành công (ResponseCode: " + responseCode + ", TransactionStatus: " + transactionStatus + ")");
            order.setCompletedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.save(order);

            recordPaymentEvent(order.getProvider(), "VNP_FAIL_" + (transactionNo != null ? transactionNo : order.getId()), order.getId(), "PAYMENT_FAILED", "ResponseCode: " + responseCode + ", Status: " + transactionStatus);
            log.info("VNPAY IPN DEPOSIT FAILED | orderId={} | responseCode={} | transactionStatus={}", order.getId(), responseCode, transactionStatus);

            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
            return response;
        }
    }

    /**
     * Hiển thị kết quả thanh toán trên trình duyệt từ Return URL của VNPay.
     * TUYỆT ĐỐI KHÔNG HẠCH TOÁN SỐ DƯ TẠI ĐÂY (chỉ phục vụ giao diện người dùng).
     * Kiểm tra trạng thái đơn thực tế từ Database để tránh báo thành công sớm trước khi IPN tới.
     * Đồng thời escape toàn bộ dữ liệu HTML phòng chống XSS.
     */
    public String renderVnPayReturnHtml(Map<String, String> params) {
        boolean validSignature = (vnPayConfig != null && vnPayConfig.isConfigured())
                && VnPayUtil.verifySignature(params, vnPayConfig.getHashSecret());

        String rawResponseCode = params.get("vnp_ResponseCode");
        String rawTxnRef = params.get("vnp_TxnRef");
        String rawVnpAmountStr = params.get("vnp_Amount");
        String rawTransactionNo = params.get("vnp_TransactionNo");

        String safeResponseCode = escapeHtml(rawResponseCode);
        String safeTxnRef = escapeHtml(rawTxnRef);
        String safeTransactionNo = escapeHtml(rawTransactionNo);

        String title;
        String badgeText;
        String badgeClass;
        String message;

        if (!validSignature) {
            title = "Cảnh báo chữ ký không hợp lệ";
            badgeText = "CHỮ KÝ KHÔNG HỢP LỆ";
            badgeClass = "status-failed";
            message = "Chữ ký số không khớp với hệ thống. Giao dịch bị từ chối.";
        } else {
            // Tra cứu trạng thái đơn hàng thực tế trong database
            PaymentOrder order = null;
            if (rawTxnRef != null && !rawTxnRef.isBlank()) {
                try {
                    Long orderId = Long.parseLong(rawTxnRef.trim());
                    order = paymentOrderRepository.findById(orderId).orElse(null);
                } catch (NumberFormatException e) {
                    order = paymentOrderRepository.findByCheckoutToken(rawTxnRef.trim()).orElse(null);
                }
                if (order == null) {
                    order = paymentOrderRepository.findByCheckoutToken(rawTxnRef.trim()).orElse(null);
                }
            }

            if (order == null) {
                title = "Không tìm thấy giao dịch";
                badgeText = "ĐƠN HÀNG KHÔNG TỒN TẠI";
                badgeClass = "status-failed";
                message = "Không tìm thấy thông tin đơn hàng tương ứng trong hệ thống.";
            } else if (order.getStatus() == PaymentStatus.SUCCEEDED) {
                title = "Thanh toán VNPay Sandbox thành công";
                badgeText = "THÀNH CÔNG";
                badgeClass = "status-success";
                message = "Giao dịch đã được máy chủ xác nhận (IPN) và hạch toán vào ví vốn ảo của bạn.";
            } else if (order.getStatus() == PaymentStatus.FAILED || order.getStatus() == PaymentStatus.CANCELLED) {
                title = "Thanh toán thất bại hoặc đã hủy";
                badgeText = "THẤT BẠI";
                badgeClass = "status-failed";
                message = "Giao dịch không thành công hoặc đã bị hủy (Mã phản hồi: " + safeResponseCode + ").";
            } else {
                // Đang PENDING hoặc PROCESSING (IPN chưa đến hoặc đang xử lý)
                title = "Đang xác nhận giao dịch VNPay Sandbox";
                badgeText = "ĐANG XÁC NHẬN";
                badgeClass = "status-pending";
                message = "Hệ thống đang chờ máy chủ VNPay gửi tín hiệu xác nhận (IPN). Số dư ví sẽ được cập nhật tự động ngay khi IPN được xử lý.";
            }
        }

        String formattedAmount = "";
        try {
            if (rawVnpAmountStr != null && !rawVnpAmountStr.isBlank()) {
                long vnd = Long.parseLong(rawVnpAmountStr.trim()) / 100;
                formattedAmount = String.format("%,d VND", vnd);
            }
        } catch (Exception ignored) {
        }
        String safeFormattedAmount = escapeHtml(formattedAmount);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"vi\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>" + escapeHtml(title) + "</title>\n" +
                "    <link rel=\"stylesheet\" href=\"/vnpay-return.css\">\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"warning-badge\">⚠️ MÔI TRƯỜNG SANDBOX — KHÔNG PHẢI TIỀN THẬT</div><br/>\n" +
                "        <div class=\"status-badge " + escapeHtml(badgeClass) + "\">" + escapeHtml(badgeText) + "</div>\n" +
                "        <h2>" + escapeHtml(title) + "</h2>\n" +
                "        <p>" + escapeHtml(message) + "</p>\n" +
                "        <table class=\"info-table\">\n" +
                "            <tr><td>Mã đơn hàng:</td><td>#" + (safeTxnRef.isEmpty() ? "--" : safeTxnRef) + "</td></tr>\n" +
                "            <tr><td>Số tiền VNPay:</td><td>" + (safeFormattedAmount.isEmpty() ? "--" : safeFormattedAmount) + "</td></tr>\n" +
                "            <tr><td>Mã giao dịch VNPay:</td><td>" + (safeTransactionNo.isEmpty() ? "--" : safeTransactionNo) + "</td></tr>\n" +
                "            <tr><td>Chữ ký điện tử:</td><td>" + (validSignature ? "Hợp lệ" : "Không hợp lệ") + "</td></tr>\n" +
                "        </table>\n" +
                "        <a href=\"fnmf://payment/return\" class=\"btn\">Quay lại ứng dụng FNMF</a>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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