package com.llmgateway.controller;

import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.exception.UnauthorizedException;
import com.llmgateway.service.ContentRefreshQuotaService;
import com.llmgateway.service.ForecastService;
import com.llmgateway.util.JwtUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private static final Logger log = LoggerFactory.getLogger(ForecastController.class);

    private final ForecastService forecastService;
    private final ContentRefreshQuotaService quotaService;
    private final JwtUtil jwtUtil;

    public ForecastController(ForecastService forecastService) {
        this(forecastService, null, null);
    }

    @Autowired
    public ForecastController(ForecastService forecastService,
                              ContentRefreshQuotaService quotaService,
                              JwtUtil jwtUtil) {
        this.forecastService = forecastService;
        this.quotaService = quotaService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * GET /api/forecast/{symbol}?timeframe=24H_7D
     * Lấy phân tích dự báo xu hướng AI cho 1 mã tài sản (Ví dụ: BTCUSDT, ETHUSDT, XAUUSD, USOIL)
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<ForecastResponse> getForecastBySymbol(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "24H_7D") String timeframe) {
        ForecastRequest request = new ForecastRequest(symbol, timeframe);
        ForecastResponse response = forecastService.generateForecast(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/forecast/analyze
     * Tạo hoặc lấy bản dự báo thị trường AI (ưu tiên cache 15 phút)
     */
    @PostMapping("/analyze")
    public ResponseEntity<ForecastResponse> analyzeForecast(@Valid @RequestBody ForecastRequest request) {
        ForecastResponse response = forecastService.generateForecast(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/forecast/refresh
     * Làm mới thủ công cưỡng bức (Pull-to-refresh) có kiểm soát hạn mức dùng chung.
     * Bỏ qua cache 15 phút, yêu cầu JWT hợp lệ và Client-Request-ID duy nhất.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshForecast(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "Client-Request-ID", required = false) String headerRequestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ForecastRequest request) {
        Long userId = extractUserId(authHeader);

        String clientRequestId = headerRequestId;
        if (clientRequestId == null || clientRequestId.isBlank()) {
            clientRequestId = idempotencyKey;
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            clientRequestId = request.getClientRequestId();
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("Thiếu Header Client-Request-ID hoặc Idempotency-Key cho thao tác làm mới!");
        }

        if (quotaService == null) {
            throw new IllegalStateException("ContentRefreshQuotaService chưa được cấu hình");
        }

        // 1. Trừ lượt hạn mức dùng chung (Idempotent & Concurrency-safe)
        RefreshQuotaDto quota = quotaService.acquireRefreshQuota(userId, clientRequestId, "FORECAST");

        // 2. Replay cùng Client-Request-ID: Tuyệt đối không gọi lại Gemini provider
        if (quota.isReplay()) {
            log.info("REPLAY FORECAST REQUEST | userId={} | clientRequestId={} -> Trả kết quả từ cache nếu có, không gọi provider",
                    userId, clientRequestId);
            Optional<ForecastResponse> cached = forecastService.getFreshForecastFromCacheOnly(request.getSymbol());
            if (cached.isPresent()) {
                ForecastResponse resp = cached.get();
                resp.applyQuota(quota);
                return ResponseEntity.ok(resp);
            }
            log.warn("REPLAY FORECAST REQUEST | Cache không tồn tại cho symbol={} -> Trả FORECAST_UNAVAILABLE an toàn, không gọi provider",
                    request.getSymbol());
            return buildForecastUnavailableResponse(quota);
        }

        // 3. Gọi làm mới dự báo bỏ qua cache 15 phút
        try {
            ForecastResponse response = forecastService.generateForecast(request, true);
            response.applyQuota(quota);
            return ResponseEntity.ok(response);
        } catch (ForecastUnavailableException | MarketDataUnavailableException ex) {
            log.warn("Lỗi provider khi làm mới Forecast sau khi đã trừ lượt: {}", ex.getMessage());
            return buildForecastUnavailableResponse(quota);
        }
    }

    private ResponseEntity<Map<String, Object>> buildForecastUnavailableResponse(RefreshQuotaDto quota) {
        Map<String, Object> errResp = new HashMap<>();
        errResp.put("status", "ERROR");
        errResp.put("code", "FORECAST_UNAVAILABLE");
        errResp.put("message", "Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.");
        if (quota != null) {
            errResp.put("maxDailyRefreshes", quota.getMaxDailyRefreshes());
            errResp.put("usedRefreshes", quota.getUsedRefreshes());
            errResp.put("remainingRefreshes", quota.getRemainingRefreshes());
            errResp.put("quotaDate", quota.getQuotaDate());
        }
        errResp.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errResp);
    }

    /**
     * GET /api/forecast/history/{symbol}
     * Lấy lịch sử các bản dự báo AI trong quá khứ của 1 mã tài sản
     */
    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<MarketForecast>> getForecastHistory(@PathVariable String symbol) {
        List<MarketForecast> history = forecastService.getForecastHistory(symbol);
        return ResponseEntity.ok(history);
    }

    /**
     * GET /api/forecast/latest
     * Lấy 10 bản dự báo AI mới nhất của toàn hệ thống
     */
    @GetMapping("/latest")
    public ResponseEntity<List<MarketForecast>> getLatestForecasts() {
        List<MarketForecast> latest = forecastService.getLatestForecasts();
        return ResponseEntity.ok(latest);
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
        if (jwtUtil == null || !jwtUtil.validateToken(token)) {
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
