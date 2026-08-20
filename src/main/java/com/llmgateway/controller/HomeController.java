package com.llmgateway.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    /**
     * Tự động chuyển hướng từ trang chủ "/" hoặc "/swagger" thẳng vào giao diện Swagger UI
     * Giúp người dùng hoặc Giảng viên mở bất kỳ link nào cũng xem được tài liệu API ngay lập tức.
     */
    @GetMapping({"/", "/swagger", "/docs", "/swagger-ui.html"})
    public String redirectToSwagger() {
        return "redirect:/swagger-ui/index.html";
    }
}
