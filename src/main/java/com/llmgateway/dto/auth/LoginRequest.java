package com.llmgateway.dto.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

public class LoginRequest {

    @NotBlank(message = "Email không được để trống")
    @JsonAlias("username")
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
