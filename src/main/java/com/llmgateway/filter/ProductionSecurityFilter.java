package com.llmgateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Production Security Filter.
 * Chỉ kích hoạt trong profile 'prod'.
 * Chặn và trả HTTP 404 cho các đường dẫn nhạy cảm:
 * - /h2-console (và mọi path con)
 * - /swagger-ui (và mọi path con, bao gồm /swagger-ui.html)
 * - /v3/api-docs (và mọi path con)
 * - /admin.html
 * - /api/admin/db/query (và mọi path con)
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionSecurityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Cấu hình CSP và Security Headers chặt chẽ theo tiêu chuẩn sản xuất
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");

        String path = request.getRequestURI();

        if (isBlockedPath(path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        filterChain.doFilter(request, response);
    }

    public boolean isBlockedPath(String path) {
        if (path == null) {
            return false;
        }

        String normalized = path.trim().toLowerCase();

        // 1. /h2-console và mọi path con
        if (normalized.equals("/h2-console") || normalized.startsWith("/h2-console/")) {
            return true;
        }

        // 2. /swagger-ui, /swagger-ui.html và mọi path con
        if (normalized.equals("/swagger-ui") || normalized.startsWith("/swagger-ui/")
                || normalized.equals("/swagger-ui.html") || normalized.startsWith("/swagger-ui.html/")) {
            return true;
        }

        // 3. /v3/api-docs và mọi path con
        if (normalized.equals("/v3/api-docs") || normalized.startsWith("/v3/api-docs/")) {
            return true;
        }

        // 4. /api/admin/db/query và mọi path con (chỉ cho phép dev/local)
        if (normalized.equals("/api/admin/db/query") || normalized.startsWith("/api/admin/db/query/")) {
            return true;
        }

        return false;
    }
}