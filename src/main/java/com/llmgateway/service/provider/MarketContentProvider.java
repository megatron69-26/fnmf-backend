package com.llmgateway.service.provider;

import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.NewsArticleDto;

import java.math.BigDecimal;
import java.util.List;

public interface MarketContentProvider {

    boolean supports(String symbol);

    BigDecimal getLatestPrice(String symbol);

    List<CandleDto> getCandles(String symbol, String interval, int limit);

    List<NewsArticleDto> getLatestNews(String symbol, int limit);

    String providerName();
}
