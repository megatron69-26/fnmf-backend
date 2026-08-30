package com.llmgateway.dto.auth;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    private String username;
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String username, String password) {
        this.username = username;
        this.email = username;
        this.password = password;
    }

    public String getUsername() {
        return username != null && !username.isBlank() ? username : email;
    }

    public void setUsername(String username) {
        this.username = username;
        if (this.email == null) this.email = username;
    }

    public String getEmail() {
        return email != null && !email.isBlank() ? email : username;
    }

    public void setEmail(String email) {
        this.email = email;
        if (this.username == null) this.username = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
