package com.llmgateway.service.provider;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.NewsArticleDto;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.service.BinanceMarketClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class BinanceMarketDataProvider implements MarketContentProvider {

    public static final String PROVIDER_NAME = "BINANCE";

    private static final Set<String> SUPPORTED_SYMBOLS = Set.of(
            MarketSymbolConfig.CANONICAL_BTC,
            MarketSymbolConfig.CANONICAL_ETH,
            MarketSymbolConfig.CANONICAL_XAU
    );

    private final BinanceMarketClient binanceMarketClient;

    public BinanceMarketDataProvider(BinanceMarketClient binanceMarketClient) {
        this.binanceMarketClient = binanceMarketClient;
    }

    @Override
    public boolean supports(String symbol) {
        if (symbol == null) return false;
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        return SUPPORTED_SYMBOLS.contains(canonical);
    }

    @Override
    public BigDecimal getLatestPrice(String symbol) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        MarketSymbolConfig.SymbolMeta meta = MarketSymbolConfig.getMeta(canonical);
        try {
            BinanceMarketClient.BinanceTickerResult ticker = binanceMarketClient.fetch24hrTicker(meta.binanceSymbol());
            return ticker.price();
        } catch (Exception e) {
            throw new MarketDataUnavailableException("Không lấy được giá Binance cho mã: " + canonical);
        }
    }

    @Override
    public List<CandleDto> getCandles(String symbol, String interval, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        MarketSymbolConfig.SymbolMeta meta = MarketSymbolConfig.getMeta(canonical);
        String binanceInterval = ("1m".equalsIgnoreCase(interval)) ? "1m" : "1d";
        try {
            return binanceMarketClient.fetchKlines(meta.binanceSymbol(), binanceInterval, limit);
        } catch (Exception e) {
            throw new MarketDataUnavailableException("Không lấy được nến Binance cho mã: " + canonical);
        }
    }

    @Override
    public List<NewsArticleDto> getLatestNews(String symbol, int limit) {
        return Collections.emptyList();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}
