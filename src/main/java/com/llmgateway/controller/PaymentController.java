package com.llmgateway.controller;

import com.llmgateway.dto.payment.CreatePaymentRequest;
import com.llmgateway.dto.payment.PaymentOrderResponseDto;
import com.llmgateway.service.PaymentService;
import com.llmgateway.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final JwtUtil jwtUtil;

    public PaymentController(PaymentService paymentService, JwtUtil jwtUtil) {
        this.paymentService = paymentService;
        this.jwtUtil = jwtUtil;
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new com.llmgateway.exception.UnauthorizedException("Vui lòng đính kèm Bearer Token hợp lệ trong Header Authorization!");
        }
        String token = authHeader.trim();
        if (token.startsWith("Bearer ") || token.startsWith("bearer ")) {
            token = token.substring(7).trim();
        }
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (!jwtUtil.validateToken(token)) {
            log.warn("Xác thực Bearer token thất bại hoặc token đã hết hạn");
            throw new com.llmgateway.exception.UnauthorizedException("Token không hợp lệ hoặc đã hết hạn! Vui lòng đăng nhập lại để lấy token mới.");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new com.llmgateway.exception.UnauthorizedException("Không thể xác định danh tính người dùng từ Token!");
        }
        return userId;
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        } catch (Exception e) {
            String scheme = request.getScheme();
            String serverName = request.getServerName();
            int port = request.getServerPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                return scheme + "://" + serverName;
            }
            return scheme + "://" + serverName + ":" + port;
        }
    }

    /**
     * POST /api/payments/deposits
     * Tạo yêu cầu nạp USD mô phỏng (Sandbox Deposit).
     */
    @PostMapping("/deposits")
    public ResponseEntity<PaymentOrderResponseDto> createDeposit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {
        Long userId = extractUserId(authHeader);
        String baseUrl = resolveBaseUrl(httpRequest);
        PaymentOrderResponseDto response = paymentService.createDeposit(userId, request, baseUrl);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/payments/withdrawals
     * Tạo yêu cầu rút USD mô phỏng (Sandbox Withdrawal).
     */
    @PostMapping("/withdrawals")
    public ResponseEntity<PaymentOrderResponseDto> createWithdrawal(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {
        Long userId = extractUserId(authHeader);
        String baseUrl = resolveBaseUrl(httpRequest);
        PaymentOrderResponseDto response = paymentService.createWithdrawal(userId, request, baseUrl);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/payments
     * Lấy danh sách lịch sử nạp/rút tiền Sandbox của người dùng.
     */
    @GetMapping
    public ResponseEntity<List<PaymentOrderResponseDto>> getUserPayments(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest httpRequest) {
        Long userId = extractUserId(authHeader);
        String baseUrl = resolveBaseUrl(httpRequest);
        List<PaymentOrderResponseDto> list = paymentService.getUserPaymentOrders(userId, baseUrl);
        return ResponseEntity.ok(list);
    }

    /**
     * GET /api/payments/{paymentOrderId}
     * Xem chi tiết trạng thái của 1 giao dịch payment.
     */
    @GetMapping("/{paymentOrderId}")
    public ResponseEntity<PaymentOrderResponseDto> getPaymentDetails(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long paymentOrderId,
            HttpServletRequest httpRequest) {
        Long userId = extractUserId(authHeader);
        String baseUrl = resolveBaseUrl(httpRequest);
        PaymentOrderResponseDto dto = paymentService.getPaymentOrder(userId, paymentOrderId, baseUrl);
        return ResponseEntity.ok(dto);
    }

    /**
     * POST /api/payments/{paymentOrderId}/cancel
     * Hủy yêu cầu nạp/rút đang ở trạng thái PENDING.
     */
    @PostMapping("/{paymentOrderId}/cancel")
    public ResponseEntity<PaymentOrderResponseDto> cancelPayment(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long paymentOrderId,
            HttpServletRequest httpRequest) {
        Long userId = extractUserId(authHeader);
        String baseUrl = resolveBaseUrl(httpRequest);
        PaymentOrderResponseDto dto = paymentService.cancelPaymentOrder(userId, paymentOrderId, baseUrl);
        return ResponseEntity.ok(dto);
    }
}