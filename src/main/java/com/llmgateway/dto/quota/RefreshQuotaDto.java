package com.llmgateway.dto.quota;

public class RefreshQuotaDto {

    private int maxDailyRefreshes;
    private int usedRefreshes;
    private int remainingRefreshes;
    private String quotaDate;
    private boolean replay;

    public RefreshQuotaDto() {
    }

    public RefreshQuotaDto(int maxDailyRefreshes, int usedRefreshes, int remainingRefreshes, String quotaDate) {
        this(maxDailyRefreshes, usedRefreshes, remainingRefreshes, quotaDate, false);
    }

    public RefreshQuotaDto(int maxDailyRefreshes, int usedRefreshes, int remainingRefreshes, String quotaDate, boolean replay) {
        this.maxDailyRefreshes = maxDailyRefreshes;
        this.usedRefreshes = usedRefreshes;
        this.remainingRefreshes = remainingRefreshes;
        this.quotaDate = quotaDate;
        this.replay = replay;
    }

    public boolean isReplay() {
        return replay;
    }

    public void setReplay(boolean replay) {
        this.replay = replay;
    }

    public int getMaxDailyRefreshes() {
        return maxDailyRefreshes;
    }

    public void setMaxDailyRefreshes(int maxDailyRefreshes) {
        this.maxDailyRefreshes = maxDailyRefreshes;
    }

    public int getUsedRefreshes() {
        return usedRefreshes;
    }

    public void setUsedRefreshes(int usedRefreshes) {
        this.usedRefreshes = usedRefreshes;
    }

    public int getRemainingRefreshes() {
        return remainingRefreshes;
    }

    public void setRemainingRefreshes(int remainingRefreshes) {
        this.remainingRefreshes = remainingRefreshes;
    }

    public String getQuotaDate() {
        return quotaDate;
    }

    public void setQuotaDate(String quotaDate) {
        this.quotaDate = quotaDate;
    }
}
