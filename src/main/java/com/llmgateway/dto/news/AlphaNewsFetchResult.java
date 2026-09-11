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

    public static final String ALPHA_RATE_LIMITED = "ALPHA_RATE_LIMITED";
    public static final String ALPHA_HTTP_ERROR = "ALPHA_HTTP_ERROR";
    public static final String ALPHA_INVALID_RESPONSE = "ALPHA_INVALID_RESPONSE";
    public static final String ALPHA_NETWORK_ERROR = "ALPHA_NETWORK_ERROR";

    public static AlphaNewsFetchResult rateLimited() {
        return new AlphaNewsFetchResult(Status.UNAVAILABLE, Collections.emptyList(), ALPHA_RATE_LIMITED);
    }

    public static AlphaNewsFetchResult httpError() {
        return new AlphaNewsFetchResult(Status.UNAVAILABLE, Collections.emptyList(), ALPHA_HTTP_ERROR);
    }

    public static AlphaNewsFetchResult invalidResponse() {
        return new AlphaNewsFetchResult(Status.UNAVAILABLE, Collections.emptyList(), ALPHA_INVALID_RESPONSE);
    }

    public static AlphaNewsFetchResult networkError() {
        return new AlphaNewsFetchResult(Status.UNAVAILABLE, Collections.emptyList(), ALPHA_NETWORK_ERROR);
    }

    public static AlphaNewsFetchResult unavailable(String safeReason) {
        String clean = (safeReason != null && !safeReason.isBlank()) ? safeReason.trim() : ALPHA_NETWORK_ERROR;
        return new AlphaNewsFetchResult(Status.UNAVAILABLE, Collections.emptyList(), clean);
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