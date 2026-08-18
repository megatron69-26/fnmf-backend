package com.llmgateway.controller;

import com.llmgateway.dto.auth.AuthResponse;
import com.llmgateway.dto.auth.LoginRequest;
import com.llmgateway.dto.auth.RegisterRequest;
import com.llmgateway.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller phụ trách các API Xác thực (Auth) & Quản lý Tài khoản.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/auth/register
     * Đăng ký tài khoản mới và tự động cấp ví ảo $10,000.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/login
     * Đăng nhập tài khoản, trả về JWT Token và thông tin số dư ví.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/auth/me
     * Lấy thông tin user hiện tại qua header Authorization: Bearer <token>.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getProfile(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AuthResponse response = authService.getProfile(authHeader);
        return ResponseEntity.ok(response);
    }
}
