package com.llmgateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                // Cấu hình Server URL tương đối "/" để Swagger UI tự động khớp theo HTTPS của Cloudflare hoặc HTTP của localhost
                .servers(List.of(new Server().url("/").description("Current Active Host (Auto HTTPS / HTTP)")))
                .info(new Info()
                        .title("FNMF - Financial News & Market Forecasting REST API")
                        .description("Tài liệu đặc tả toàn bộ REST API cho Hệ thống Backend FNMF (Bao gồm Xác thực Auth, Dữ liệu Thị trường Alpha Vantage, Phân tích Tin tức Gemini AI, Danh mục Theo dõi Watchlist, Giao dịch Giả lập Paper Trading và Dự báo Thị trường AI).")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Đặng Đức Khôi (Backend / Data Developer)")
                                .email("khoidang2353@gmail.com"))
                        .license(new License().name("Apache 2.0").url("http://springdoc.org")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Nhập chuỗi JWT Token nhận được từ API /api/auth/login để xác thực các API cá nhân.")));
    }
}
