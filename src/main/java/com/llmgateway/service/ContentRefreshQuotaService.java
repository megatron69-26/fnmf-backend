package com.llmgateway.service;

import com.llmgateway.dto.quota.RefreshQuotaDto;
import com.llmgateway.entity.ContentRefreshEvent;
import com.llmgateway.entity.UserDailyRefreshQuota;
import com.llmgateway.exception.DailyRefreshLimitReachedException;
import com.llmgateway.repository.ContentRefreshEventRepository;
import com.llmgateway.repository.UserDailyRefreshQuotaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
public class ContentRefreshQuotaService {

    private static final Logger log = LoggerFactory.getLogger(ContentRefreshQuotaService.class);

    public static final int MAX_DAILY_REFRESHES = 5;
    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserDailyRefreshQuotaRepository quotaRepository;
    private final ContentRefreshEventRepository eventRepository;
    private final PlatformTransactionManager transactionManager;
    private Clock clock;

    @Autowired
    public ContentRefreshQuotaService(UserDailyRefreshQuotaRepository quotaRepository,
                                      ContentRefreshEventRepository eventRepository,
                                      @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this(quotaRepository, eventRepository, Clock.system(VIETNAM_ZONE), transactionManager);
    }

    public ContentRefreshQuotaService(UserDailyRefreshQuotaRepository quotaRepository,
                                      ContentRefreshEventRepository eventRepository,
                                      Clock clock,
                                      PlatformTransactionManager transactionManager) {
        this.quotaRepository = quotaRepository;
        this.eventRepository = eventRepository;
        this.clock = clock != null ? clock : Clock.system(VIETNAM_ZONE);
        this.transactionManager = transactionManager;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Clock getClock() {
        return clock;
    }

    public LocalDate getCurrentVietnamDate() {
        return LocalDate.now(clock);
    }

    /**
     * Lấy trạng thái hạn mức đọc (Read-only), không trừ lượt, không tạo khóa.
     */
    @Transactional(readOnly = true)
    public RefreshQuotaDto getQuotaStatus(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId không được để trống");
        }
        LocalDate today = getCurrentVietnamDate();
        int used = quotaRepository.findByUserIdAndQuotaDate(userId, today)
                .map(UserDailyRefreshQuota::getUsedCount)
                .orElse(0);
        int remaining = Math.max(0, MAX_DAILY_REFRESHES - used);
        return new RefreshQuotaDto(MAX_DAILY_REFRESHES, used, remaining, today.toString(), false);
    }

    public RefreshQuotaDto acquireRefreshQuota(Long userId, String clientRequestId, String module) {
        LocalDate today = getCurrentVietnamDate();
        ensureQuotaRowExists(userId, today);

        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
            return template.execute(status -> executeAcquireQuota(userId, clientRequestId, module, today));
        } else {
            return executeAcquireQuota(userId, clientRequestId, module, today);
        }
    }

    private void ensureQuotaRowExists(Long userId, LocalDate today) {
        if (transactionManager != null) {
            TransactionTemplate newTx = new TransactionTemplate(transactionManager);
            newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                newTx.execute(status -> {
                    if (quotaRepository.findByUserIdAndQuotaDate(userId, today).isEmpty()) {
                        quotaRepository.saveAndFlush(new UserDailyRefreshQuota(userId, today, 0));
                    }
                    return null;
                });
            } catch (Exception ex) {
                log.debug("Concurrent quota row creation handled: {}", ex.getMessage());
            }
        } else {
            try {
                if (quotaRepository.findByUserIdAndQuotaDate(userId, today).isEmpty()) {
                    quotaRepository.saveAndFlush(new UserDailyRefreshQuota(userId, today, 0));
                }
            } catch (Exception ex) {
                log.debug("Concurrent quota row creation handled: {}", ex.getMessage());
            }
        }
    }

    private RefreshQuotaDto executeAcquireQuota(Long userId, String clientRequestId, String module, LocalDate today) {
        if (userId == null) {
            throw new IllegalArgumentException("userId không được để trống");
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("clientRequestId không được để trống");
        }
        String cleanRequestId = clientRequestId.trim();
        String cleanModule = (module != null && !module.isBlank()) ? module.trim().toUpperCase() : "UNKNOWN";

        // 1. Kiểm tra tính lũy thừa (Replay check)
        Optional<ContentRefreshEvent> existingEvent = eventRepository.findByUserIdAndClientRequestId(userId, cleanRequestId);
        if (existingEvent.isPresent()) {
            ContentRefreshEvent prev = existingEvent.get();
            log.info("REPLAY REFRESH REQUEST | userId={} | clientRequestId={} | prevStatus={} | module={}",
                    userId, cleanRequestId, prev.getStatus(), prev.getModule());
            RefreshQuotaDto status = getQuotaStatus(userId);
            status.setReplay(true);
            if ("ACCEPTED".equalsIgnoreCase(prev.getStatus())) {
                return status;
            } else {
                throw new DailyRefreshLimitReachedException(status);
            }
        }

        // 2. Khóa dòng bản ghi hạn mức hôm nay (Pessimistic Lock)
        UserDailyRefreshQuota quotaRow = quotaRepository.findByUserIdAndQuotaDateForUpdate(userId, today)
                .orElseGet(() -> {
                    try {
                        return quotaRepository.saveAndFlush(new UserDailyRefreshQuota(userId, today, 0));
                    } catch (Exception ex) {
                        return quotaRepository.findByUserIdAndQuotaDateForUpdate(userId, today)
                                .orElseThrow(() -> new IllegalStateException("Không thể khóa bản ghi hạn mức"));
                    }
                });

        // 3. Kiểm tra hạn mức 5 lượt
        if (quotaRow.getUsedCount() >= MAX_DAILY_REFRESHES) {
            ContentRefreshEvent rejectedEvent = new ContentRefreshEvent(userId, today, cleanRequestId, cleanModule, "REJECTED");
            try {
                eventRepository.saveAndFlush(rejectedEvent);
            } catch (Exception ex) {
                log.debug("Event already recorded: {}", ex.getMessage());
            }
            RefreshQuotaDto status = new RefreshQuotaDto(MAX_DAILY_REFRESHES, quotaRow.getUsedCount(), 0, today.toString());
            log.warn("REFRESH QUOTA EXHAUSTED | userId={} | module={} | date={} | used={}",
                    userId, cleanModule, today, quotaRow.getUsedCount());
            throw new DailyRefreshLimitReachedException(status);
        }

        // 4. Trừ lượt thành công
        int newUsed = quotaRow.getUsedCount() + 1;
        quotaRow.setUsedCount(newUsed);
        quotaRow.setUpdatedAt(LocalDateTime.now(clock.withZone(VIETNAM_ZONE)));
        quotaRepository.saveAndFlush(quotaRow);

        ContentRefreshEvent acceptedEvent = new ContentRefreshEvent(userId, today, cleanRequestId, cleanModule, "ACCEPTED");
        try {
            eventRepository.saveAndFlush(acceptedEvent);
        } catch (Exception ex) {
            log.debug("Event already recorded: {}", ex.getMessage());
        }

        int remaining = Math.max(0, MAX_DAILY_REFRESHES - newUsed);
        log.info("REFRESH QUOTA DEDUCTED | userId={} | module={} | date={} | used={}/{} | remaining={}",
                userId, cleanModule, today, newUsed, MAX_DAILY_REFRESHES, remaining);

        return new RefreshQuotaDto(MAX_DAILY_REFRESHES, newUsed, remaining, today.toString());
    }
}
