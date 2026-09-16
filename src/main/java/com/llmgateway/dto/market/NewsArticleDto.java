package com.llmgateway.dto.market;

public record NewsArticleDto(
        String title,
        String summary,
        String url,
        String publishedAt,
        String source
) {
}
