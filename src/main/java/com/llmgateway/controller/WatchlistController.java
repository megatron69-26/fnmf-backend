package com.llmgateway.controller;

import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.dto.watchlist.WatchlistRequest;
import com.llmgateway.service.WatchlistService;
import com.llmgateway.util.JwtUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistController.class);

    private final WatchlistService watchlistService;
    private final JwtUtil jwtUtil;

    public WatchlistController(WatchlistService watchlistService, JwtUtil jwtUtil) {
        this.watchlistService = watchlistService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * GET /api/watchlist
     * Lấy danh sách các mã tài sản đang theo dõi của User (yêu cầu Bearer Token).
     */
    @GetMapping
    public ResponseEntity<List<WatchlistItemDto>> getWatchlist(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = extractUserId(authHeader);
        List<WatchlistItemDto> items = watchlistService.getUserWatchlist(userId);
        return ResponseEntity.ok(items);
    }

    /**
     * POST /api/watchlist
     * Thêm một mã mới vào danh sách theo dõi (Body: { "symbol": "BTCUSDT" }).
     */
    @PostMapping
    public ResponseEntity<WatchlistItemDto> addToWatchlist(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody WatchlistRequest request) {
        Long userId = extractUserId(authHeader);
        WatchlistItemDto item = watchlistService.addToWatchlist(userId, request);
        return ResponseEntity.ok(item);
    }

    /**
     * DELETE /api/watchlist/{symbol}
     * Xóa một mã khỏi danh sách theo dõi.
     */
    @DeleteMapping("/{symbol}")
    public ResponseEntity<Map<String, String>> removeFromWatchlist(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String symbol) {
        Long userId = extractUserId(authHeader);
        watchlistService.removeFromWatchlist(userId, symbol);
        return ResponseEntity.ok(Map.of("message", "Đã xóa " + symbol.toUpperCase() + " khỏi danh mục theo dõi!"));
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new IllegalArgumentException("Vui lòng đính kèm Bearer Token hợp lệ trong Header Authorization!");
        }
        String token = authHeader.trim();
        if (token.startsWith("Bearer ") || token.startsWith("bearer ")) {
            token = token.substring(7).trim();
        }
        if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (!jwtUtil.validateToken(token)) {
            log.warn("Token không hợp lệ: '{}'", token);
            throw new IllegalArgumentException("Token không hợp lệ hoặc đã hết hạn! Vui lòng đăng nhập lại để lấy token mới.");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new IllegalArgumentException("Không thể xác định danh tính người dùng từ Token!");
        }
        return userId;
    }
}
