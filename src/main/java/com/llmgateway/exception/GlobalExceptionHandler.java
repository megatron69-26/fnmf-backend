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
