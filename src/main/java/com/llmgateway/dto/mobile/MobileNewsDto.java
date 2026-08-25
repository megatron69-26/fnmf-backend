package com.llmgateway.dto.mobile;

/**
 * DTO đồng bộ 100% với Room Entity "News" của bạn Mạnh (DungQuenTenAnh-1.0.0).
 *
 * Mapping:
 *   Room (Mạnh)          ←→  Backend (Khôi)
 *   ────────────────────────────────────────
 *   newsId (String PK)   ←   "NEWS_" + id (Long từ Oracle NEWS_AI_CACHE)
 *   title  (String)      ←   title (từ NEWS_AI_CACHE)
 *   url    (String)      ←   articleUrl (từ NEWS_AI_CACHE)
 *   publishedAt (long)   ←   publishedAt (TIMESTAMP → Unix epoch millis)
 */
public class MobileNewsDto {

    private String newsId;     // Khớp với Room @PrimaryKey String newsId
    private String title;      // Khớp với Room public String title
    private String url;        // Khớp với Room public String url
    private long publishedAt;  // Khớp với Room public long publishedAt (Unix epoch millis)

    public MobileNewsDto() {
    }

    public MobileNewsDto(String newsId, String title, String url, long publishedAt) {
        this.newsId = newsId;
        this.title = title;
        this.url = url;
        this.publishedAt = publishedAt;
    }

    public String getNewsId() {
        return newsId;
    }

    public void setNewsId(String newsId) {
        this.newsId = newsId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(long publishedAt) {
        this.publishedAt = publishedAt;
    }
}
