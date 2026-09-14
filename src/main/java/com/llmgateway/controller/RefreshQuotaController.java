package com.llmgateway.controller;

import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.exception.UnauthorizedException;
import com.llmgateway.service.ContentRefreshQuotaService;
import com.llmgateway.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller cung cấp trạng thái hạn mức làm mới thủ công (Read-only).
 * Không trừ lượt và yêu cầu Bearer Token hợp lệ.
 */
@RestController
@RequestMapping("/api/refresh-quota")
public class RefreshQuotaController {

    private static final Logger log = LoggerFactory.getLogger(RefreshQuotaController.class);

    private final ContentRefreshQuotaService quotaService;
    private final JwtUtil jwtUtil;

    public RefreshQuotaController(ContentRefreshQuotaService quotaService, JwtUtil jwtUtil) {
        this.quotaService = quotaService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * GET /api/refresh-quota/status
     * Lấy hạn mức còn lại trong ngày hôm nay của người dùng.
     */
    @GetMapping("/status")
    public ResponseEntity<RefreshQuotaDto> getQuotaStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = extractUserId(authHeader);
        RefreshQuotaDto status = quotaService.getQuotaStatus(userId);
        return ResponseEntity.ok(status);
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new UnauthorizedException("Vui lòng đính kèm Bearer Token hợp lệ trong Header Authorization!");
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
            throw new UnauthorizedException("Token không hợp lệ hoặc đã hết hạn! Vui lòng đăng nhập lại để lấy token mới.");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new UnauthorizedException("Không thể xác định danh tính người dùng từ Token!");
        }
        return userId;
    }
}
