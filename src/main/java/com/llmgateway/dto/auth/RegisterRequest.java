package com.llmgateway.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    private String username;
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 4, message = "Mật khẩu phải có ít nhất 4 ký tự")
    private String password;

    private String fullName;

    public RegisterRequest() {
    }

    public RegisterRequest(String username, String password) {
        this.username = username;
        this.email = username;
        this.password = password;
        this.fullName = username;
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

    public String getFullName() {
        return fullName != null && !fullName.isBlank() ? fullName : getUsername();
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
