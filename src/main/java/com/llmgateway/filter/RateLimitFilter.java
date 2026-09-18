package com.llmgateway.filter;

import com.llmgateway.util.JwtUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate Limiter — Giới hạn số request theo IP / User trong một khoảng thời gian.
 *
 * Chính sách phân cấp:
 * 1. Các endpoint tĩnh / tài liệu / giao dịch khớp lệnh: Bỏ qua kiểm tra.
 * 2. GET /api/watchlist/ai-insights: Giữ giới hạn NGHIÊM NGẶT (mặc định 10 req/phút) vì tốn tài nguyên AI & tin tức.
 * 3. Watchlist CRUD (GET/POST/DELETE /api/watchlist): Áp dụng hạn mức riêng theo user/method (60 GET, 30 POST, 30 DELETE/phút)
 *    đáp ứng thao tác người dùng thêm/xóa nhiều mã nhanh chóng nhưng vẫn ngăn chặn lạm dụng hoặc DoS.
 * 4. Các endpoint khác: Áp dụng giới hạn mặc định maxRequests (10 req/phút).
 */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    @Value("${gateway.rate-limit.max-requests:10}")
    private int maxRequests = 10;

    @Value("${gateway.rate-limit.window-seconds:60}")
    private int windowSeconds = 60;

    @Value("${gateway.rate-limit.watchlist.get-max:60}")
    private int watchlistGetMax = 60;

    @Value("${gateway.rate-limit.watchlist.mutation-max:30}")
    private int watchlistMutationMax = 30;

    private final JwtUtil jwtUtil;
    private final Map<String, RateLimitEntry> rateLimitCounters = new ConcurrentHashMap<>();

    public RateLimitFilter() {
        this(null);
    }

    @Autowired
    public RateLimitFilter(@Autowired(required = false) JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public void clearCounters() {
        rateLimitCounters.clear();
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String path = request.getRequestURI();

        // 1. Bỏ qua rate limit cho các API giao dịch, thị trường công khai, admin và trang tài liệu tĩnh
        if (path.startsWith("/api/trade") || path.startsWith("/api/admin") || path.startsWith("/api/market")
                || path.startsWith("/h2-console") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIdentifier = resolveClientIdentifier(request);

        // 2. Phân nhánh kiểm soát cho /api/watchlist
        if (path.startsWith("/api/watchlist")) {
            if (path.equals("/api/watchlist/ai-insights") || path.startsWith("/api/watchlist/ai-insights/")) {
                // Endpoint AI Insights: Giới hạn chặt chẽ (mặc định 10 req/phút) để bảo vệ tài nguyên LLM/News
                String aiKey = clientIdentifier + ":ai-insights";
                if (isRateLimited(aiKey, maxRequests, windowSeconds)) {
                    sendRateLimitResponse(response, aiKey, maxRequests);
                    return;
                }
            } else {
                // Watchlist CRUD cơ bản (GET, POST, DELETE): Áp dụng hạn mức riêng theo user và HTTP method
                String method = request.getMethod() != null ? request.getMethod().toUpperCase(Locale.ROOT) : "GET";
                int methodLimit = getWatchlistMethodLimit(method);
                String watchlistKey = clientIdentifier + ":watchlist:" + method;
                if (isRateLimited(watchlistKey, methodLimit, windowSeconds)) {
                    sendRateLimitResponse(response, watchlistKey, methodLimit);
                    return;
                }
            }
            chain.doFilter(request, response);
            return;
        }

        // 3. Các endpoint còn lại: Giới hạn mặc định theo IP/User
        if (isRateLimited(clientIdentifier, maxRequests, windowSeconds)) {
            sendRateLimitResponse(response, clientIdentifier, maxRequests);
            return;
        }

        chain.doFilter(request, response);
    }

    private int getWatchlistMethodLimit(String method) {
        return switch (method) {
            case "GET" -> watchlistGetMax;
            case "POST", "DELETE" -> watchlistMutationMax;
            default -> watchlistMutationMax;
        };
    }

    private boolean isRateLimited(String key, int max, int windowSec) {
        long now = System.currentTimeMillis();
        RateLimitEntry entry = rateLimitCounters.computeIfAbsent(key, k -> new RateLimitEntry(now));

        if (now - entry.windowStart > windowSec * 1000L) {
            entry.reset(now);
        }

        int currentCount = entry.counter.incrementAndGet();
        if (currentCount > max) {
            log.warn("RATE LIMITED | key={} | count={} | max={}", key, currentCount, max);
            return true;
        }
        return false;
    }

    private void sendRateLimitResponse(HttpServletResponse response, String key, int max) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\": \"Too many requests. Please wait and try again.\", \"status\": 429}"
        );
    }

    private String resolveClientIdentifier(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isBlank() && jwtUtil != null) {
            try {
                String token = authHeader.trim();
                if (token.startsWith("Bearer ") || token.startsWith("bearer ")) {
                    token = token.substring(7).trim();
                }
                if (token.startsWith("\"") && token.endsWith("\"") && token.length() > 1) {
                    token = token.substring(1, token.length() - 1).trim();
                }
                Long userId = jwtUtil.getUserIdFromToken(token);
                if (userId != null) {
                    return "user:" + userId;
                }
            } catch (Exception ignored) {
            }
        }
        return "ip:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class RateLimitEntry {
        final AtomicInteger counter = new AtomicInteger(0);
        volatile long windowStart;

        RateLimitEntry(long windowStart) {
            this.windowStart = windowStart;
        }

        void reset(long newWindowStart) {
            this.windowStart = newWindowStart;
            this.counter.set(0);
        }
    }
}
