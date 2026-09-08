package com.llmgateway.entity;

/**
 * Phân quyền người dùng trong hệ thống FNMF:
 * - USER: Người dùng thông thường (Paper Trading, Xem tin tức, Quản lý ví cá nhân)
 * - ADMIN: Quản trị viên hệ thống (Backoffice Cloud Admin, Quản lý người dùng và số dư)
 */
public enum UserRole {
    USER,
    ADMIN
}
