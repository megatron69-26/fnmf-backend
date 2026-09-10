package com.llmgateway;

import com.llmgateway.filter.ProductionSecurityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test kiểm tra cấu hình bảo mật cho Profile Production (Railway).
 * Chạy hoàn toàn độc lập, không cần kết nối Database thật và không lock H2.
 */
public class ProductionProfileTest {

    private final ProductionSecurityFilter filter = new ProductionSecurityFilter();

    @Test
    public void testProductionSecurityFilter_blocksSensitiveEndpoints() throws Exception {
        String[] sensitivePaths = {
                "/h2-console",
                "/h2-console/login.do",
                "/swagger-ui",
                "/swagger-ui/",
                "/swagger-ui.html",
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/api/admin/db/query",
                "/api/admin/db/query/test"
        };

        for (String path : sensitivePaths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(404, response.getStatus(), "Path should be blocked with 404: " + path);
            assertTrue(filter.isBlockedPath(path), "isBlockedPath should be true for: " + path);
        }
    }

    @Test
    public void testProductionSecurityFilter_allowsStandardEndpoints() throws Exception {
        String[] allowedPaths = {
                "/api/market/prices",
                "/api/news/sync",
                "/api/mobile/news/sync",
                "/actuator/health",
                "/api/auth/login",
                "/api/trade/portfolio",
                "/api/admin/db/overview",
                "/admin.html",
                "/admin.css",
                "/admin.js",
                "/vnpay-return.css"
        };

        for (String path : allowedPaths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(200, response.getStatus(), "Path should pass through filter: " + path);
            assertFalse(filter.isBlockedPath(path), "isBlockedPath should be false for: " + path);

            // Xác minh CSP và Security Headers
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
            assertNotNull(response.getHeader("Content-Security-Policy"));
            assertTrue(response.getHeader("Content-Security-Policy").contains("default-src 'self'"));
        }
    }

    @Test
    public void testProductionProperties_hasNoHardcodedSecretsAndEnforcesPostgreSQL() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application-prod.properties")) {
            assertNotNull(is, "application-prod.properties must exist on classpath");
            props.load(is);
        }

        // 1. Kiểm tra không hardcode secrets
        assertEquals("${JWT_SECRET}", props.getProperty("jwt.secret"));
        assertEquals("${ALPHAVANTAGE_API_KEY}", props.getProperty("alphavantage.api.key"));
        assertEquals("${OPENAI_API_KEY}", props.getProperty("openai.api.key"));

        // 2. Kiểm tra tắt các tính năng debug/developer trên production
        assertEquals("false", props.getProperty("spring.h2.console.enabled"));
        assertEquals("false", props.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("false", props.getProperty("springdoc.api-docs.enabled"));

        // 3. Kiểm tra PostgreSQL configuration
        assertEquals("org.postgresql.Driver", props.getProperty("spring.datasource.driver-class-name"));
        assertEquals("org.hibernate.dialect.PostgreSQLDialect", props.getProperty("spring.jpa.database-platform"));

        // 4. Kiểm tra cấu hình Flyway baseline
        assertEquals("true", props.getProperty("spring.flyway.enabled"));
        assertEquals("true", props.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("0", props.getProperty("spring.flyway.baseline-version"));
        assertEquals("classpath:db/migration", props.getProperty("spring.flyway.locations"));
    }
}