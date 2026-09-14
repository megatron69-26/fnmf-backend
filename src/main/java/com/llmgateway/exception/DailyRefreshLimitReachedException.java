package com.llmgateway.exception;

import com.llmgateway.dto.quota.RefreshQuotaDto;

public class DailyRefreshLimitReachedException extends RuntimeException {

    private final RefreshQuotaDto quota;

    public DailyRefreshLimitReachedException(RefreshQuotaDto quota) {
        super("Bạn đã dùng hết lượt làm mới hôm nay");
        this.quota = quota;
    }

    public DailyRefreshLimitReachedException(String message, RefreshQuotaDto quota) {
        super(message != null ? message : "Bạn đã dùng hết lượt làm mới hôm nay");
        this.quota = quota;
    }

    public RefreshQuotaDto getQuota() {
        return quota;
    }

    public int getMaxDailyRefreshes() {
        return quota != null ? quota.getMaxDailyRefreshes() : 5;
    }

    public int getUsedRefreshes() {
        return quota != null ? quota.getUsedRefreshes() : 5;
    }

    public int getRemainingRefreshes() {
        return quota != null ? quota.getRemainingRefreshes() : 0;
    }

    public String getQuotaDate() {
        return quota != null ? quota.getQuotaDate() : "";
    }
}
