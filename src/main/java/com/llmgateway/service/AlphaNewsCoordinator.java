package com.llmgateway.service;

import com.llmgateway.dto.news.AlphaNewsFetchResult;
import com.llmgateway.dto.news.NewsFeedItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Điều phối tần suất gọi Alpha Vantage News & Cơ chế Single-Flight / Cooldown theo loại lỗi.
 * Bảo vệ hạn mức 25 request/ngày của gói miễn phí Alpha Vantage:
 * 1. Khoảng làm mới mặc định tối thiểu 90 phút (alphavantage.news.refresh-interval-minutes).
 * 2. Cooldown phân loại theo lỗi:
 *    - ALPHA_RATE_LIMITED: 1.440 phút (24 giờ) (alphavantage.news.rate-limit-cooldown-minutes).
 *    - Lỗi khác (HTTP, network, invalid response): 15 phút (alphavantage.news.failure-cooldown-minutes).
 *    - Gemini failure cooldown: 10 phút (alphavantage.news.gemini-cooldown-minutes).
 * 3. Tách thành công Alpha khỏi thành công toàn pipeline:
 *    - Lưu immutable CachedAlphaSnapshot (scope, status, rawItems, fetchedAt) trong RAM.
 *    - Hỗ trợ tái sử dụng raw snapshot để retry Gemini khi Gemini gặp sự cố mà KHÔNG gọi lại Alpha.
 *    - Giữ đúng trạng thái SUCCESS_EMPTY trong 90 phút, không biến thành degraded.
 *    - So khớp scope/symbol: Snapshot BTC không dùng cho ETH; snapshot GLOBAL dùng chung cho toàn thị trường.
 * 4. Single-flight: Khóa refreshLock bao phủ toàn bộ pipeline (Alpha fetch/reuse -> Gemini -> DB persist -> Result).
 * 5. Hỗ trợ Clock injectable/testable, không phụ thuộc sleep trong test.
 */
@Service
public class AlphaNewsCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AlphaNewsCoordinator.class);

    public static final String ALPHA_RATE_LIMITED = "ALPHA_RATE_LIMITED";
    public static final String ALPHA_HTTP_ERROR = "ALPHA_HTTP_ERROR";
    public static final String ALPHA_INVALID_RESPONSE = "ALPHA_INVALID_RESPONSE";
    public static final String ALPHA_NETWORK_ERROR = "ALPHA_NETWORK_ERROR";

    @Value("${alphavantage.news.refresh-interval-minutes:90}")
    private long refreshIntervalMinutes = 90;

    @Value("${alphavantage.news.failure-cooldown-minutes:15}")
    private long failureCooldownMinutes = 15;

    @Value("${alphavantage.news.rate-limit-cooldown-minutes:1440}")
    private long rateLimitCooldownMinutes = 1440;

    @Value("${alphavantage.news.gemini-cooldown-minutes:10}")
    private long geminiCooldownMinutes = 10;

    private Clock clock = Clock.systemDefaultZone();

    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile long lastAlphaSuccessTime = 0L;
    private volatile long lastPipelineSuccessTime = 0L;
    private volatile long lastFailureTime = 0L;
    private volatile String lastFailureCode = null;
    private volatile long lastGeminiFailureTime = 0L;

    // Lưu trữ immutable snapshot từ Alpha Vantage trong bộ nhớ
    private volatile CachedAlphaSnapshot cachedSnapshot = null;
    private volatile List<NewsFeedItemDto> lastRawAlphaFeed = Collections.emptyList();
    private volatile String lastRawAlphaSymbol = null;

    /**
     * Snapshot bất biến lưu trữ kết quả của provider Alpha Vantage
     */
    public static class CachedAlphaSnapshot {
        private final String scope;
        private final AlphaNewsFetchResult.Status status;
        private final List<NewsFeedItemDto> rawItems;
        private final long fetchedAt;

        public CachedAlphaSnapshot(String scope, AlphaNewsFetchResult.Status status, List<NewsFeedItemDto> rawItems, long fetchedAt) {
            this.scope = normalizeScope(scope);
            this.status = status;
            this.rawItems = (rawItems != null) ? Collections.unmodifiableList(new ArrayList<>(rawItems)) : Collections.emptyList();
            this.fetchedAt = fetchedAt;
        }

        public String getScope() {
            return scope;
        }

        public AlphaNewsFetchResult.Status getStatus() {
            return status;
        }

        public List<NewsFeedItemDto> getRawItems() {
            return rawItems;
        }

        public long getFetchedAt() {
            return fetchedAt;
        }

        public boolean isFresh(Clock clock, long refreshIntervalMinutes) {
            if (fetchedAt <= 0) {
                return false;
            }
            return (clock.millis() - fetchedAt) < (refreshIntervalMinutes * 60_000L);
        }

        public boolean matchesScope(String requestedScope) {
            String normThis = normalizeScope(this.scope);
            String normReq = normalizeScope(requestedScope);
            if (normThis.equals(normReq)) {
                return true;
            }
            // Global snapshot có thể phục vụ mọi symbol / request để tiết kiệm 25 req/ngày
            if ("GLOBAL".equals(normThis)) {
                return true;
            }
            return false;
        }
    }

    public static String normalizeScope(String symbolOrScope) {
        if (symbolOrScope == null || symbolOrScope.trim().isBlank()) {
            return "GLOBAL";
        }
        return symbolOrScope.trim().toUpperCase();
    }

    public boolean isAlphaFresh() {
        long now = clock.millis();
        if (cachedSnapshot != null) {
            return cachedSnapshot.isFresh(clock, refreshIntervalMinutes);
        }
        return lastAlphaSuccessTime > 0 && (now - lastAlphaSuccessTime) < (refreshIntervalMinutes * 60_000L);
    }

    public boolean isCacheFresh(LocalDateTime latestAnalyzedAt) {
        if (latestAnalyzedAt == null) {
            return false;
        }
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(refreshIntervalMinutes);
        return latestAnalyzedAt.isAfter(threshold);
    }

    public boolean isInCooldown() {
        long now = clock.millis();
        if (lastFailureTime <= 0) {
            return false;
        }
        long cooldownMs = ALPHA_RATE_LIMITED.equals(lastFailureCode)
                ? (rateLimitCooldownMinutes * 60_000L)
                : (failureCooldownMinutes * 60_000L);
        return (now - lastFailureTime) < cooldownMs;
    }

    public boolean isGeminiInCooldown() {
        long now = clock.millis();
        if (lastGeminiFailureTime <= 0) {
            return false;
        }
        return (now - lastGeminiFailureTime) < (geminiCooldownMinutes * 60_000L);
    }

    public boolean tryAcquireRefresh() {
        return refreshLock.tryLock();
    }

    public void releaseRefresh() {
        if (refreshLock.isHeldByCurrentThread()) {
            refreshLock.unlock();
        }
    }

    public void recordAlphaSnapshot(String scope, AlphaNewsFetchResult.Status status, List<NewsFeedItemDto> rawItems) {
        long now = clock.millis();
        this.lastAlphaSuccessTime = now;
        this.lastFailureTime = 0L;
        this.lastFailureCode = null;
        this.cachedSnapshot = new CachedAlphaSnapshot(scope, status, rawItems, now);
        this.lastRawAlphaFeed = this.cachedSnapshot.getRawItems();
        this.lastRawAlphaSymbol = normalizeScope(scope);
    }

    public void recordAlphaSuccess(List<NewsFeedItemDto> rawItems, String symbol) {
        AlphaNewsFetchResult.Status status = (rawItems != null && !rawItems.isEmpty())
                ? AlphaNewsFetchResult.Status.SUCCESS_WITH_ITEMS
                : AlphaNewsFetchResult.Status.SUCCESS_EMPTY;
        recordAlphaSnapshot(symbol, status, rawItems);
    }

    public void recordPipelineSuccess() {
        this.lastPipelineSuccessTime = clock.millis();
        this.lastFailureTime = 0L;
        this.lastFailureCode = null;
    }

    public void recordGeminiFailure() {
        this.lastGeminiFailureTime = clock.millis();
    }

    public void recordGeminiSuccess() {
        this.lastGeminiFailureTime = 0L;
    }

    public void recordSuccess() {
        recordAlphaSuccess(null, null);
        recordPipelineSuccess();
    }

    public void recordFailure(String safeCode) {
        this.lastFailureTime = clock.millis();
        this.lastFailureCode = safeCode != null ? safeCode : ALPHA_NETWORK_ERROR;
    }

    public CachedAlphaSnapshot getCachedSnapshot() {
        return cachedSnapshot;
    }

    public void setCachedSnapshot(CachedAlphaSnapshot snapshot) {
        this.cachedSnapshot = snapshot;
        if (snapshot != null) {
            this.lastAlphaSuccessTime = snapshot.getFetchedAt();
            this.lastRawAlphaFeed = snapshot.getRawItems();
            this.lastRawAlphaSymbol = normalizeScope(snapshot.getScope());
        }
    }

    public List<NewsFeedItemDto> getLastRawAlphaFeed() {
        return lastRawAlphaFeed;
    }

    public String getLastRawAlphaSymbol() {
        return lastRawAlphaSymbol;
    }

    public void clearRawAlphaFeed() {
        this.cachedSnapshot = null;
        this.lastRawAlphaFeed = Collections.emptyList();
        this.lastRawAlphaSymbol = null;
    }

    public Clock getClock() {
        return clock;
    }

    public void setClock(Clock clock) {
        this.clock = (clock != null) ? clock : Clock.systemDefaultZone();
    }

    public long getRefreshIntervalMinutes() {
        return refreshIntervalMinutes;
    }

    public void setRefreshIntervalMinutes(long refreshIntervalMinutes) {
        this.refreshIntervalMinutes = refreshIntervalMinutes;
    }

    public long getFailureCooldownMinutes() {
        return failureCooldownMinutes;
    }

    public void setFailureCooldownMinutes(long failureCooldownMinutes) {
        this.failureCooldownMinutes = failureCooldownMinutes;
    }

    public long getRateLimitCooldownMinutes() {
        return rateLimitCooldownMinutes;
    }

    public void setRateLimitCooldownMinutes(long rateLimitCooldownMinutes) {
        this.rateLimitCooldownMinutes = rateLimitCooldownMinutes;
    }

    public long getGeminiCooldownMinutes() {
        return geminiCooldownMinutes;
    }

    public void setGeminiCooldownMinutes(long geminiCooldownMinutes) {
        this.geminiCooldownMinutes = geminiCooldownMinutes;
    }

    public long getLastAlphaSuccessTime() {
        return lastAlphaSuccessTime;
    }

    public void setLastAlphaSuccessTime(long lastAlphaSuccessTime) {
        this.lastAlphaSuccessTime = lastAlphaSuccessTime;
    }

    public long getLastPipelineSuccessTime() {
        return lastPipelineSuccessTime;
    }

    public void setLastPipelineSuccessTime(long lastPipelineSuccessTime) {
        this.lastPipelineSuccessTime = lastPipelineSuccessTime;
    }

    public long getLastSuccessTime() {
        return lastPipelineSuccessTime > 0 ? lastPipelineSuccessTime : lastAlphaSuccessTime;
    }

    public void setLastSuccessTime(long lastSuccessTime) {
        this.lastAlphaSuccessTime = lastSuccessTime;
        this.lastPipelineSuccessTime = lastSuccessTime;
    }

    public long getLastFailureTime() {
        return lastFailureTime;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    public long getLastGeminiFailureTime() {
        return lastGeminiFailureTime;
    }

    public void setLastGeminiFailureTime(long lastGeminiFailureTime) {
        this.lastGeminiFailureTime = lastGeminiFailureTime;
    }

    public void reset() {
        this.lastAlphaSuccessTime = 0L;
        this.lastPipelineSuccessTime = 0L;
        this.lastFailureTime = 0L;
        this.lastFailureCode = null;
        this.lastGeminiFailureTime = 0L;
        this.cachedSnapshot = null;
        this.lastRawAlphaFeed = Collections.emptyList();
        this.lastRawAlphaSymbol = null;
        while (refreshLock.isHeldByCurrentThread()) {
            refreshLock.unlock();
        }
    }
}
