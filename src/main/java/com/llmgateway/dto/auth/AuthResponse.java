package com.llmgateway.dto.auth;

public class AuthResponse {
    private String token;
    private String tokenType = "Bearer";
    private UserDto user;
    private WalletDto wallet;
    private String message;

    public AuthResponse() {
    }

    public AuthResponse(String token, UserDto user, WalletDto wallet, String message) {
        this.token = token;
        this.tokenType = "Bearer";
        this.user = user;
        this.wallet = wallet;
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }

    public WalletDto getWallet() {
        return wallet;
    }

    public void setWallet(WalletDto wallet) {
        this.wallet = wallet;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
