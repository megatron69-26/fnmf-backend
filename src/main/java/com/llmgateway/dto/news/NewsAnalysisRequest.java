package com.llmgateway.dto.news;

import jakarta.validation.constraints.NotBlank;

public class NewsAnalysisRequest {

    @NotBlank(message = "Tiêu đề bài báo không được để trống")
    private String title;

    @NotBlank(message = "Nội dung bài báo không được để trống")
    private String content;

    private String symbol; // Ví dụ: "XAUUSD", "BTCUSDT", "USOIL"
    private String articleUrl; // Đường dẫn bài báo (dùng làm key cache trong CSDL)

    public NewsAnalysisRequest() {
    }

    public NewsAnalysisRequest(String title, String content, String symbol, String articleUrl) {
        this.title = title;
        this.content = content;
        this.symbol = symbol;
        this.articleUrl = articleUrl;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getArticleUrl() {
        return articleUrl;
    }

    public void setArticleUrl(String articleUrl) {
        this.articleUrl = articleUrl;
    }
}
