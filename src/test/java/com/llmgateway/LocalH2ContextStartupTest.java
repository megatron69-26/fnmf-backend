package com.llmgateway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Context Startup Test:
 * Chứng minh rằng Backend khởi động với H2 in-memory tạm thời,
 * ApplicationContext load thành công và Flyway hoàn toàn không chạy (tránh lỗi PostgreSQL DO $$).
 * Tuyệt đối không dùng hoặc ghi đè file data/fnmf.mv.db thật.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_local_startup_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "alphavantage.api.key=test_api_key",
        "openai.api.key=test_api_key"
})
public class LocalH2ContextStartupTest {

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private Flyway flyway;

    @Test
    @DisplayName("1. ApplicationContext khởi động thành công với H2 in-memory và Flyway bị vô hiệu hóa")
    public void testContextLoadsAndFlywayIsDisabled() {
        assertNotNull(context, "ApplicationContext phải được khởi tạo thành công");
        assertNull(flyway, "Flyway bean tuyệt đối không được khởi tạo khi spring.flyway.enabled=false");
    }

    @Test
    @DisplayName("2. Xác minh application.properties và application-phone.properties đều có spring.flyway.enabled=false")
    public void testPropertiesFilesHaveFlywayDisabled() throws Exception {
        Properties defaultProps = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(is, "application.properties phải tồn tại");
            defaultProps.load(is);
        }
        assertEquals("false", defaultProps.getProperty("spring.flyway.enabled"),
                "application.properties phải có spring.flyway.enabled=false để bảo vệ H2 local");

        Properties phoneProps = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application-phone.properties")) {
            assertNotNull(is, "application-phone.properties phải tồn tại");
            phoneProps.load(is);
        }
        assertEquals("false", phoneProps.getProperty("spring.flyway.enabled"),
                "application-phone.properties phải có spring.flyway.enabled=false để bảo vệ H2 phone");
    }
}
