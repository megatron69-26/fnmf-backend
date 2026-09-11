package com.llmgateway.dto.news;

import java.util.Collections;
import java.util.List;

public class NewsSyncResult {
    private String status; // "ok", "degraded", "empty"
    private String message;
    private List<NewsFeedItemDto> items;

    public NewsSyncResult() {
        this.items = Collections.emptyList();
    }

    public NewsSyncResult(String status, String message, List<NewsFeedItemDto> items) {
        this.status = status;
        this.message = message;
        this.items = items != null ? items : Collections.emptyList();
    }

    public static NewsSyncResult ok(List<NewsFeedItemDto> items) {
        return new NewsSyncResult("ok", null, items);
    }

    public static NewsSyncResult empty(String message) {
        return new NewsSyncResult("empty", message != null ? message : "Chưa có bản tin mới", Collections.emptyList());
    }

    public static NewsSyncResult degraded(String message) {
        return new NewsSyncResult("degraded", message != null ? message : "Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng", Collections.emptyList());
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<NewsFeedItemDto> getItems() {
        return items;
    }

    public void setItems(List<NewsFeedItemDto> items) {
        this.items = items != null ? items : Collections.emptyList();
    }
}