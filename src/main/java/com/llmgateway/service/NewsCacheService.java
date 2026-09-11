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
        return saveCachedArticle(articleUrl, title, symbol, summaryPointsJson, sentiment, confidencePct, reason, publishedAt, analyzedAt, author, source, originalSummary, bannerImage, originalTitle, null, null);
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
                                                  String originalTitle,
                                                  String displayTitleVi,
                                                  String bulletPointsVi) {
        return saveCachedArticle(articleUrl, title, symbol, summaryPointsJson, sentiment, confidencePct, reason, publishedAt, analyzedAt, author, source, originalSummary, bannerImage, originalTitle, displayTitleVi, null, bulletPointsVi);
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
                                                  String originalTitle,
                                                  String displayTitleVi,
                                                  String displaySummaryVi,
                                                  String bulletPointsVi) {
        if (articleUrl == null || articleUrl.isBlank()) {
            log.warn("Không thể lưu cache do articleUrl rỗng.");
            return Optional.empty();
        }
        String cleanUrl = articleUrl.trim();

        // Chống trùng lặp theo articleUrl nhưng cho phép làm giàu lại nếu bài cũ thiếu displayTitleVi hoặc displaySummaryVi
        Optional<NewsAiCache> existing = repository.findByArticleUrl(cleanUrl);
        if (existing.isPresent()) {
            NewsAiCache current = existing.get();
            boolean needsReEnrichment = current.getDisplayTitleVi() == null
                    || current.getDisplayTitleVi().isBlank()
                    || current.getDisplaySummaryVi() == null
                    || current.getDisplaySummaryVi().isBlank()
                    || !NewsLocalizationQualityPolicy.isValidDisplayTitleVi(current.getDisplayTitleVi(), current.getOriginalTitle())
                    || !NewsLocalizationQualityPolicy.isValidDisplaySummaryVi(current.getDisplaySummaryVi(), current.getOriginalSummary(), current.getDisplayTitleVi());

            if (needsReEnrichment && displayTitleVi != null && NewsLocalizationQualityPolicy.isValidDisplayTitleVi(displayTitleVi, originalTitle)) {
                current.setDisplayTitleVi(displayTitleVi.trim());
                if (displaySummaryVi != null && !displaySummaryVi.isBlank()) {
                    current.setDisplaySummaryVi(displaySummaryVi.trim());
                }
                if (bulletPointsVi != null && !bulletPointsVi.isBlank()) {
                    current.setBulletPointsVi(bulletPointsVi.trim());
                }
                if (originalTitle != null && !originalTitle.isBlank()) {
                    current.setOriginalTitle(originalTitle.trim());
                }
                if (originalSummary != null && !originalSummary.isBlank()) {
                    current.setOriginalSummary(originalSummary.trim());
                }
                if (source != null && !source.isBlank()) {
                    current.setSource(NewsPublisherResolver.resolvePublisher(source, cleanUrl));
                }
                NewsAiCache updated = repository.save(current);
                log.info("LÀM GIÀU LẠI BẢN DỊCH CHO BÀI CACHE CŨ | id={} | url='{}' | titleVi='{}' | summaryVi='{}'",
                        updated.getId(), cleanUrl, updated.getDisplayTitleVi(), updated.getDisplaySummaryVi());
                return Optional.of(updated);
            }
            log.debug("Bài báo đã tồn tại trong cache với bản dịch hợp lệ, bỏ qua: {}", cleanUrl);
            return existing;
        }

        String resolvedPublisher = NewsPublisherResolver.resolvePublisher(source, cleanUrl);
        String finalOriginalTitle = (originalTitle != null && !originalTitle.isBlank())
                ? originalTitle.trim()
                : (title != null ? title.trim() : "");
        String finalDisplayTitle = (displayTitleVi != null && !displayTitleVi.isBlank())
                ? displayTitleVi.trim()
                : null;
        String finalDisplaySummary = (displaySummaryVi != null && !displaySummaryVi.isBlank())
                ? displaySummaryVi.trim()
                : null;
        String finalBulletPointsVi = (bulletPointsVi != null && !bulletPointsVi.isBlank())
                ? bulletPointsVi.trim()
                : summaryPointsJson;

        NewsAiCache entity = new NewsAiCache();
        entity.setId(null); // Không dùng id cũ, để CSDL tự sinh identity
        entity.setArticleUrl(cleanUrl);
        entity.setTitle(finalOriginalTitle); // Lưu tiêu đề gốc vào title
        entity.setSymbol(symbol != null && !symbol.isBlank() ? symbol.toUpperCase().trim() : "MARKET");
        entity.setSummaryPoints(summaryPointsJson);
        entity.setSentiment(sentiment != null ? sentiment : "NEUTRAL");
        entity.setConfidencePct(confidencePct != null ? confidencePct : BigDecimal.valueOf(80));
        entity.setReason(reason);
        entity.setPublishedAt(publishedAt != null ? publishedAt : LocalDateTime.now());
        entity.setAnalyzedAt(analyzedAt != null ? analyzedAt : LocalDateTime.now());
        entity.setAuthor(author != null && !author.isBlank() ? author.trim() : null);
        entity.setSource(resolvedPublisher);
        entity.setOriginalSummary(originalSummary != null && !originalSummary.isBlank() ? originalSummary.trim() : null);
        entity.setBannerImage(bannerImage != null && !bannerImage.isBlank() ? bannerImage.trim() : null);
        entity.setOriginalTitle(finalOriginalTitle); // Bảo toàn originalTitle độc lập
        entity.setDisplayTitleVi(finalDisplayTitle);
        entity.setDisplaySummaryVi(finalDisplaySummary);
        entity.setBulletPointsVi(finalBulletPointsVi);

        NewsAiCache saved = repository.save(entity);
        log.info("LƯU BÀI BÁO THẬT VÀO CSDL CACHE | id={} | url='{}' | sentiment={} | author='{}' | publisher='{}' | titleVi='{}'",
                saved.getId(), cleanUrl, sentiment, saved.getAuthor(), saved.getSource(), saved.getDisplayTitleVi());
        return Optional.of(saved);
    }

    /**
     * Dọn dẹp và chuẩn hóa các bản ghi cũ trong Cache mà không truncate bảng:
     * - Cập nhật source generic ("Financial News", "Tin thị trường") thành publisher chuẩn dựa trên URL.
     * - Bổ sung displayTitleVi nếu đang null.
     */
    @Transactional
    public int cleanupLegacyCacheSources() {
        List<NewsAiCache> all = repository.findAll();
        int updatedCount = 0;
        for (NewsAiCache item : all) {
            boolean modified = false;
            if (NewsPublisherResolver.isGeneric(item.getSource())) {
                String resolved = NewsPublisherResolver.resolvePublisher(item.getSource(), item.getArticleUrl());
                if (!resolved.equals(item.getSource())) {
                    item.setSource(resolved);
                    modified = true;
                }
            }
            if ((item.getDisplayTitleVi() == null || item.getDisplayTitleVi().isBlank()) && item.getTitle() != null) {
                String translated = NewsHeadlineTranslator.translateHeadline(item.getTitle());
                item.setDisplayTitleVi(translated);
                modified = true;
            }
            if ((item.getBulletPointsVi() == null || item.getBulletPointsVi().isBlank()) && item.getSummaryPoints() != null) {
                item.setBulletPointsVi(item.getSummaryPoints());
                modified = true;
            }
            if (modified) {
                repository.save(item);
                updatedCount++;
            }
        }
        if (updatedCount > 0) {
            log.info("CLEANUP CACHE | Đã chuẩn hóa thành công {} bản ghi cache tin tức cũ.", updatedCount);
        }
        return updatedCount;
    }
}
