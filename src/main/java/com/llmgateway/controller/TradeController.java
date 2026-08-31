package com.llmgateway.controller;

import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.dto.trade.PortfolioSummaryDto;
import com.llmgateway.entity.Transaction;
import com.llmgateway.service.TradeService;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.util.JwtUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trade")
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final TradeService tradeService;
    private final MarketDataService marketDataService;
    private final JwtUtil jwtUtil;

    public TradeController(TradeService tradeService, MarketDataService marketDataService, JwtUtil jwtUtil) {
        this.tradeService = tradeService;
        this.marketDataService = marketDataService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * POST /api/trade/order
     * Đặt lệnh MUA (BUY) hoặc BÁN (SELL) giả lập (Paper Trading).
     */
    @PostMapping("/order")
    public ResponseEntity<?> executeOrder(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody OrderRequest request) {
        try {
            Long userId = extractUserId(authHeader);
            var priceDto = marketDataService.getPriceBySymbol(request.getSymbol());
            OrderResponse response = tradeService.executeOrder(userId, request, priceDto);
            return ResponseEntity.ok(response);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock failed during order execution: {}", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(java.util.Map.of(
                            "status", "ERROR",
                            "message", "Dữ liệu đã được cập nhật bởi thao tác khác, vui lòng thử lại."
                    ));
        }
    }

    /**
     * GET /api/trade/portfolio
     * Lấy tổng quan tài sản danh mục (Tổng giá trị, Tiền mặt khả dụng, Lời/Lỗ thời gian thực).
     */
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSummaryDto> getPortfolio(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = extractUserId(authHeader);
        PortfolioSummaryDto summary = tradeService.getPortfolioSummary(userId);
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/trade/history
     * Lấy lịch sử tất cả các lệnh Mua/Bán đã khớp của User.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Transaction>> getTransactionHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = extractUserId(authHeader);
        List<Transaction> history = tradeService.getTransactionHistory(userId);
        return ResponseEntity.ok(history);
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
