package com.llmgateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Xử lý exception toàn cục — trả JSON lỗi thay vì stacktrace.
 * Quan trọng cho production: không bao giờ lộ internal error ra client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Validation errors (ví dụ: message bị blank).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        return ResponseEntity.badRequest().body(Map.of(
                "error", errorMessage,
                "status", 400,
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Business validation errors (ví dụ: email trùng, sai pass).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", ex.getMessage() != null ? ex.getMessage() : "Bad request",
                "status", 400,
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Unsupported / rejected symbol (e.g. USOIL or non-existent symbols).
     */
    @ExceptionHandler(UnsupportedSymbolException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedSymbol(UnsupportedSymbolException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "status", "ERROR",
                "code", "UNSUPPORTED_SYMBOL",
                "message", ex.getMessage() != null ? ex.getMessage() : "Mã tài sản chưa được hỗ trợ",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Provider unavailable without cached fallback.
     */
    @ExceptionHandler(MarketDataUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleMarketDataUnavailable(MarketDataUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "ERROR",
                "code", "DATA_UNAVAILABLE",
                "message", ex.getMessage() != null ? ex.getMessage() : "Dữ liệu thị trường tạm thời không khả dụng",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Idempotency conflict (cùng key nhưng payload khác nhau).
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "ERROR",
                "code", "IDEMPOTENCY_CONFLICT",
                "message", ex.getMessage() != null ? ex.getMessage() : "Idempotency key reused with different payload",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Unauthorized errors (Token thiếu, sai hoặc hết hạn).
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", "ERROR",
                "code", "UNAUTHORIZED",
                "error", ex.getMessage() != null ? ex.getMessage() : "Unauthorized",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Checkout session expired (410 GONE).
     */
    @ExceptionHandler(CheckoutExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleCheckoutExpired(CheckoutExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "status", "ERROR",
                "code", "CHECKOUT_EXPIRED",
                "error", ex.getMessage() != null ? ex.getMessage() : "Phiên giao dịch đã hết hạn",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Số dư ví không đủ để rút tiền (422 UNPROCESSABLE_ENTITY).
     */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "status", "ERROR",
                "code", "INSUFFICIENT_BALANCE",
                "error", ex.getMessage() != null ? ex.getMessage() : "Số dư ví không đủ",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Cổng thanh toán ngoại vi không khả dụng (502 BAD_GATEWAY).
     */
    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentGatewayUnavailable(PaymentGatewayUnavailableException ex) {
        log.error("Payment gateway unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "status", "ERROR",
                "code", "PAYMENT_GATEWAY_UNAVAILABLE",
                "error", "Cổng thanh toán tạm thời không khả dụng. Vui lòng thử lại sau.",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Mọi RuntimeException khác (bao gồm lỗi từ OpenAI API).
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled runtime exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", ex.getMessage() != null ? ex.getMessage() : "Internal server error",
                "status", 500,
                "timestamp", Instant.now().toString()
        ));
    }
}
