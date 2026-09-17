package com.llmgateway.service;

import com.llmgateway.entity.Watchlist;
import com.llmgateway.repository.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class WatchlistTxWriter {

    private static final Logger log = LoggerFactory.getLogger(WatchlistTxWriter.class);

    private final WatchlistRepository watchlistRepository;

    public WatchlistTxWriter(WatchlistRepository watchlistRepository) {
        this.watchlistRepository = watchlistRepository;
    }

    /**
     * Thực hiện insert trong một physical transaction độc lập (REQUIRES_NEW).
     * Nếu xảy ra DataIntegrityViolationException (race condition do hai luồng đồng thời thêm cùng mã),
     * transaction riêng này sẽ bị rollback mà KHÔNG làm transaction ngoài bị đánh dấu rollback-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Watchlist insertInNewTx(Long userId, String cleanSymbol, Integer displayOrder) {
        Optional<Watchlist> existing = watchlistRepository.findByUserIdAndSymbol(userId, cleanSymbol);
        if (existing.isPresent()) {
            return existing.get();
        }

        Watchlist newEntry = new Watchlist();
        newEntry.setUserId(userId);
        newEntry.setSymbol(cleanSymbol);
        newEntry.setDisplayOrder(displayOrder != null ? displayOrder : 1);
        newEntry.setCreatedAt(LocalDateTime.now());
        return watchlistRepository.saveAndFlush(newEntry);
    }
}
