package com.llmgateway.service;

import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.repository.NewsAiCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service quản lý bộ nhớ đệm tin tức tài chính (News AI Cache).
 * Phân định rõ ràng:
 * - Thao tác đọc: @Transactional(readOnly = true)
 * - Thao tác ghi: @Transactional
 * Không giữ transaction CSDL mở trong lúc gọi các API HTTP bên ngoài (Alpha Vantage / Gemini).
 */
@Service
public class NewsCacheService {

    private static final Logger log = LoggerFactory.getLogger(NewsCacheService.class);

    private final NewsAiCacheRepository repository;

    public NewsCacheService(NewsAiCacheRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<NewsAiCache> findTopByOrderByPublishedAtDesc(int limit) {
        int max = limit > 0 ? limit : 5;
        List<NewsAiCache> list = repository.findTop10ByOrderByPublishedAtDesc();
        if (list.size() > max) {
            return list.subList(0, max);
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<NewsAiCache> findBySymbolOrderByPublishedAtDesc(String symbol, int limit) {
        int max = limit > 0 ? limit : 5;
        if (symbol == null || symbol.isBlank()) {
            return findTopByOrderByPublishedAtDesc(max);
        }
        List<NewsAiCache> list = repository.findBySymbolOrderByPublishedAtDesc(symbol.toUpperCase());
        if (list.isEmpty()) {
            list = findTopByOrderByPublishedAtDesc(max);
        }
        if (list.size() > max) {
            return list.subList(0, max);
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<NewsAiCache> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<NewsAiCache> findAll(int limit) {
        int max = limit > 0 ? limit : 5;
        List<NewsAiCache> all = repository.findAll();
        if (all.size() > max) {
            return all.subList(0, max);
        }
        return all;
    }

    @Transactional(readOnly = true)
    public Optional<NewsAiCache> findByArticleUrl(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            return Optional.empty();
        }
        return repository.findByArticleUrl(articleUrl.trim());
    }

    @Transactional(readOnly = true)
    public Optional<NewsAiCache> findByTitle(String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTitle(title.trim());
    }

    @Transactional(readOnly = true)
    public boolean existsByArticleUrl(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            return false;
        }
        String cleanUrl = articleUrl.trim();
        return repository.existsByArticleUrl(cleanUrl) || repository.findByArticleUrl(cleanUrl).isPresent();
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Transactional
    public NewsAiCache save(NewsAiCache entity) {
        return repository.save(entity);
    }

    @Transactional
    public List<NewsAiCache> saveAll(Iterable<NewsAiCache> entities) {
        return repository.saveAll(entities);
    }

    @Transactional
    public Optional<NewsAiCache> saveCachedArticle(String articleUrl,
                                                  String title,
                                                  String symbol,
                                                  String summaryPointsJson,
                                                  String sentiment,
                                                  BigDecimal confidencePct,
                                                  String reason,
                                                  LocalDateTime publishedAt,
                                                  LocalDateTime analyzedAt) {
        return saveCachedArticle(articleUrl, title, symbol, summaryPointsJson, sentiment, confidencePct, reason, publishedAt, analyzedAt, null, null, null, null, null);
    }

    @Transactional
    public Optional<NewsAiCache> saveCachedArticle(String articleUrl,
                                                  String title,
                                                  String symbol,
                                                  String summaryPointsJson,
                                                  String sentiment,
                                                  BigDecimal confidencePct,
                                                  String reason,
                                                  LocalDateTime publishedAt,
                                                  LocalDateTime analyzedAt,
                                                  String author,
                                                  String source,
                                                  String originalSummary,
                                                  String bannerImage,
                                                  String originalTitle) {
        if (articleUrl == null || articleUrl.isBlank()) {
            log.warn("Không thể lưu cache do articleUrl rỗng.");
            return Optional.empty();
        }
        String cleanUrl = articleUrl.trim();

        // Chống trùng lặp theo articleUrl
        Optional<NewsAiCache> existing = repository.findByArticleUrl(cleanUrl);
        if (existing.isPresent()) {
            log.debug("Bài báo đã tồn tại trong cache, bỏ qua: {}", cleanUrl);
            return existing;
        }

        NewsAiCache entity = new NewsAiCache();
        entity.setId(null); // Không dùng id cũ, để CSDL tự sinh identity
        entity.setArticleUrl(cleanUrl);
        entity.setTitle(title != null ? title.trim() : "");
        entity.setSymbol(symbol != null && !symbol.isBlank() ? symbol.toUpperCase().trim() : "MARKET");
        entity.setSummaryPoints(summaryPointsJson);
        entity.setSentiment(sentiment != null ? sentiment : "NEUTRAL");
        entity.setConfidencePct(confidencePct != null ? confidencePct : BigDecimal.valueOf(80));
        entity.setReason(reason);
        entity.setPublishedAt(publishedAt != null ? publishedAt : LocalDateTime.now());
        entity.setAnalyzedAt(analyzedAt != null ? analyzedAt : LocalDateTime.now());
        entity.setAuthor(author != null && !author.isBlank() ? author.trim() : null);
        entity.setSource(source != null && !source.isBlank() ? source.trim() : null);
        entity.setOriginalSummary(originalSummary != null && !originalSummary.isBlank() ? originalSummary.trim() : null);
        entity.setBannerImage(bannerImage != null && !bannerImage.isBlank() ? bannerImage.trim() : null);
        entity.setOriginalTitle(originalTitle != null && !originalTitle.isBlank() ? originalTitle.trim() : null);

        NewsAiCache saved = repository.save(entity);
        log.info("LƯU BÀI BÁO THẬT VÀO CSDL CACHE | id={} | url='{}' | sentiment={} | author='{}' | source='{}'",
                saved.getId(), cleanUrl, sentiment, saved.getAuthor(), saved.getSource());
        return Optional.of(saved);
    }
}
