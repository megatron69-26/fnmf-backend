package com.llmgateway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body cho endpoint /api/chat.
 * Client gửi message lên, gateway forward sang OpenAI.
 */
public class ChatRequest {

    /**
     * Model muốn dùng, ví dụ "gpt-3.5-turbo", "gpt-4o-mini".
     * Nếu không truyền, server sẽ dùng model mặc định.
     */
    private String model;

    /**
     * Nội dung tin nhắn của user.
     */
    @NotBlank(message = "Message must not be blank")
    private String message;

    // --- Constructors ---

    public ChatRequest() {
    }

    public ChatRequest(String model, String message) {
        this.model = model;
        this.message = message;
    }

    // --- Getters & Setters ---

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
