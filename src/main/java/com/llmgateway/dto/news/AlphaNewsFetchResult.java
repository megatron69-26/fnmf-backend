package com.llmgateway.dto.news;

import java.util.Collections;
import java.util.List;

public class AlphaNewsFetchResult {

    public enum Status {
        SUCCESS_WITH_ITEMS,
        SUCCESS_EMPTY,
        UNAVAILABLE
    }

    private final Status status;
    private final List<NewsFeedItemDto> items;
    private final String message;

    public AlphaNewsFetchResult(Status status, List<NewsFeedItemDto> items, String message) {
        this.status = status;
        this.items = items != null ? items : Collections.emptyList();
        this.message = message;
    }

    public static AlphaNewsFetchResult withItems(List<NewsFeedItemDto> items) {
        return new AlphaNewsFetchResult(Status.SUCCESS_WITH_ITEMS, items, null);
    }

    public static AlphaNewsFetchResult success(List<NewsFeedItemDto> items) {
        return withItems(items);
    }

    public static AlphaNewsFetchResult empty() {
        return new AlphaNewsFetchResult(Status.SUCCESS_EMPTY, Collections.emptyList(), "Alpha Vantage returned no news items");
    }

    public static AlphaNewsFetchResult unavailable(String reason) {
        return new AlphaNewsFetchResult(Status.UNAVAILABLE, Collections.emptyList(), reason);
    }

    public Status getStatus() {
        return status;
    }

    public List<NewsFeedItemDto> getItems() {
        return items;
    }

    public String getMessage() {
        return message;
    }
}