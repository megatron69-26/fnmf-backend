package com.llmgateway.dto;

/**
 * Response body trả về cho client sau khi gọi LLM API thành công.
 */
public class ChatResponse {

    /** Nội dung reply từ LLM */
    private String reply;

    /** Model đã dùng */
    private String model;

    /** Số token đã dùng */
    private Usage usage;

    // --- Constructors ---

    public ChatResponse() {
    }

    public ChatResponse(String reply, String model, Usage usage) {
        this.reply = reply;
        this.model = model;
        this.usage = usage;
    }

    // --- Getters & Setters ---

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * Thông tin token usage từ OpenAI response.
     */
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        public Usage() {
        }

        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
