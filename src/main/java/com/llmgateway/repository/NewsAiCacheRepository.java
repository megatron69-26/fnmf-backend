package com.llmgateway.repository;

import com.llmgateway.entity.NewsAiCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NewsAiCacheRepository extends JpaRepository<NewsAiCache, Long> {

    Optional<NewsAiCache> findByArticleUrl(String articleUrl);

    Optional<NewsAiCache> findByTitle(String title);
}
