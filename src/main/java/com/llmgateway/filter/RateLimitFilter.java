package com.llmgateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate Limiter — Giới hạn số request mỗi IP trong 1 khoảng thời gian.
 *
 * Tại sao cần Rate Limit?
 * - Nếu không giới hạn, một người dùng (hoặc bot) có thể gửi 10.000 request/giây.
 * - Mỗi request tốn tiền API (OpenAI tính theo token).
 * - Server sẽ bị quá tải và sập.
 *
 * Cách hoạt động (Sliding Window đơn giản):
 * - Mỗi IP được phép gửi tối đa N request trong M giây.
 * - Nếu vượt quá, server trả lỗi 429 (Too Many Requests).
 * - Sau M giây, bộ đếm tự động reset về 0.
 *
 * Lưu ý: Đây là rate limit in-memory (lưu trong RAM).
 * Khi deploy nhiều server (scale out), mỗi server có bộ đếm riêng.
 * Giải pháp Cloud nâng cao sẽ dùng Redis để chia sẻ bộ đếm giữa các server.
 */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    @Value("${gateway.rate-limit.max-requests:10}")
    private int maxRequests;

    @Value("${gateway.rate-limit.window-seconds:60}")
    private int windowSeconds;

    /**
     * Bộ nhớ lưu trữ: mỗi IP có một Entry gồm (số lần đã gọi, thời điểm bắt đầu đếm).
     * ConcurrentHashMap = HashMap an toàn cho đa luồng (nhiều user gọi cùng lúc).
     */
    private final Map<String, RateLimitEntry> ipCounters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String clientIp = getClientIp(request);
        long now = System.currentTimeMillis();

        // Lấy hoặc tạo bộ đếm cho IP này
        RateLimitEntry entry = ipCounters.computeIfAbsent(clientIp, k -> new RateLimitEntry(now));

        // Kiểm tra: nếu cửa sổ thời gian đã hết hạn, reset bộ đếm
        if (now - entry.windowStart > windowSeconds * 1000L) {
            entry.reset(now);
        }

        // Tăng bộ đếm lên 1 và kiểm tra
        int currentCount = entry.counter.incrementAndGet();

        if (currentCount > maxRequests) {
            // Vượt quá giới hạn => Từ chối phục vụ
            log.warn("RATE LIMITED | ip={} | count={} | max={}", clientIp, currentCount, maxRequests);

            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\": \"Too many requests. Please wait and try again.\", \"status\": 429}"
            );
            return;  // Dừng lại, KHÔNG cho request đi tiếp vào Controller
        }

        // Chưa vượt giới hạn => Cho request đi tiếp vào Controller bình thường
        chain.doFilter(request, response);
    }

    /**
     * Lấy IP thật của client.
     * Nếu đứng sau Load Balancer/Proxy (rất phổ biến trên Cloud),
     * IP thật nằm trong header "X-Forwarded-For", không phải getRemoteAddr().
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Entry lưu thông tin rate limit cho mỗi IP.
     */
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
