package com.llmgateway.dto.auth;

import com.llmgateway.service.AuthService;

import java.time.LocalDateTime;

public class UserDto {
    private Long id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private String role;
    private boolean needsEmailUpdate;
    private boolean validEmail;
    private LocalDateTime createdAt;

    public UserDto() {
    }

    public UserDto(Long id, String email, String fullName, String avatarUrl, LocalDateTime createdAt) {
        this(id, email, fullName, avatarUrl, "USER", createdAt);
    }

    public UserDto(Long id, String email, String fullName, String avatarUrl, String role, LocalDateTime createdAt) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.avatarUrl = avatarUrl;
        this.role = role != null ? role : "USER";
        this.validEmail = AuthService.isValidEmail(email);
        this.needsEmailUpdate = !this.validEmail;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
        this.validEmail = AuthService.isValidEmail(email);
        this.needsEmailUpdate = !this.validEmail;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getRole() {
        return role != null ? role : "USER";
    }

    public void setRole(String role) {
        this.role = role != null ? role : "USER";
    }

    public boolean isNeedsEmailUpdate() {
        return needsEmailUpdate;
    }

    public void setNeedsEmailUpdate(boolean needsEmailUpdate) {
        this.needsEmailUpdate = needsEmailUpdate;
    }

    public boolean isValidEmail() {
        return validEmail;
    }

    public void setValidEmail(boolean validEmail) {
        this.validEmail = validEmail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
