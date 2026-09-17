package com.llmgateway.dto.news;

import java.util.Collections;
import java.util.List;

public class NewsSyncResult {
    private String status; // "ok", "degraded", "empty"
    private String message;
    private List<NewsFeedItemDto> items;
    private boolean stale = false;
    private boolean fromCache = false;
    private String dataAsOf;
    private String latestPublishedAt;

    public NewsSyncResult() {
        this.items = Collections.emptyList();
    }

    public NewsSyncResult(String status, String message, List<NewsFeedItemDto> items) {
        this(status, message, items, false, false, null, null);
    }

    public NewsSyncResult(String status, String message, List<NewsFeedItemDto> items,
                          boolean stale, boolean fromCache, String dataAsOf, String latestPublishedAt) {
        if ("ok".equalsIgnoreCase(status) && (items == null || items.isEmpty())) {
            throw new IllegalArgumentException("Không thể tạo NewsSyncResult với status 'ok' khi danh sách items rỗng. Yêu cầu ít nhất 1 bài viết.");
        }
        this.status = status;
        this.message = message;
        this.items = items != null ? items : Collections.emptyList();
        this.stale = stale;
        this.fromCache = fromCache;
        this.dataAsOf = dataAsOf;
        this.latestPublishedAt = latestPublishedAt;
    }

    public static NewsSyncResult ok(List<NewsFeedItemDto> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Không thể tạo NewsSyncResult với status 'ok' khi danh sách items rỗng. Yêu cầu ít nhất 1 bài viết.");
        }
        return new NewsSyncResult("ok", null, items, false, false, null, null);
    }

    public static NewsSyncResult stale(List<NewsFeedItemDto> items, String message, String dataAsOf, String latestPublishedAt) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Không thể tạo NewsSyncResult stale khi danh sách items rỗng.");
        }
        return new NewsSyncResult("ok", message != null ? message : "Đang hiển thị tin đã lưu gần nhất",
                items, true, true, dataAsOf, latestPublishedAt);
    }

    public static NewsSyncResult empty(String message) {
        return new NewsSyncResult("empty", message != null ? message : "Chưa có bản tin mới", Collections.emptyList());
    }

    public static NewsSyncResult emptyWithCache(String message, List<NewsFeedItemDto> items, String dataAsOf, String latestPublishedAt) {
        return new NewsSyncResult("empty", message != null ? message : "Chưa có bản tin mới",
                items != null ? items : Collections.emptyList(), true, true, dataAsOf, latestPublishedAt);
    }

    public static NewsSyncResult degraded(String message) {
        return new NewsSyncResult("degraded", message != null ? message : "Dịch vụ xử lý tin tức tạm thời chưa sẵn sàng", Collections.emptyList());
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        if ("ok".equalsIgnoreCase(status) && (this.items == null || this.items.isEmpty())) {
            throw new IllegalArgumentException("Không thể thiết lập status 'ok' khi danh sách items rỗng.");
        }
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
        if ("ok".equalsIgnoreCase(this.status) && (items == null || items.isEmpty())) {
            throw new IllegalArgumentException("Không thể gán danh sách rỗng khi status đang là 'ok'.");
        }
        this.items = items != null ? items : Collections.emptyList();
    }

    public boolean isStale() {
        return stale;
    }

    public void setStale(boolean stale) {
        this.stale = stale;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }

    public String getDataAsOf() {
        return dataAsOf;
    }

    public void setDataAsOf(String dataAsOf) {
        this.dataAsOf = dataAsOf;
    }

    public String getLatestPublishedAt() {
        return latestPublishedAt;
    }

    public void setLatestPublishedAt(String latestPublishedAt) {
        this.latestPublishedAt = latestPublishedAt;
    }
}