package com.llmgateway.repository;

import com.llmgateway.entity.MarketForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketForecastRepository extends JpaRepository<MarketForecast, Long> {

    Optional<MarketForecast> findTopBySymbolOrderByCreatedAtDesc(String symbol);

    List<MarketForecast> findBySymbolOrderByCreatedAtDesc(String symbol);

    List<MarketForecast> findTop10ByOrderByCreatedAtDesc();
}
