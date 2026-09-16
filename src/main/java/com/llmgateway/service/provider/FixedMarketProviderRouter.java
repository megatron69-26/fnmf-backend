package com.llmgateway.service.provider;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.exception.UnsupportedSymbolException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FixedMarketProviderRouter {

    private final BinanceMarketDataProvider binanceProvider;
    private final AlpacaStockDataProvider alpacaProvider;
    private final TwelveDataStockDataProvider twelveDataProvider;
    private final AlphaVantageStockDataProvider alphaVantageProvider;

    public FixedMarketProviderRouter(
            BinanceMarketDataProvider binanceProvider,
            AlpacaStockDataProvider alpacaProvider,
            TwelveDataStockDataProvider twelveDataProvider,
            AlphaVantageStockDataProvider alphaVantageProvider) {
        this.binanceProvider = binanceProvider;
        this.alpacaProvider = alpacaProvider;
        this.twelveDataProvider = twelveDataProvider;
        this.alphaVantageProvider = alphaVantageProvider;
    }

    public MarketContentProvider getProvider(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Mã tài sản không được để trống");
        }

        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);

        if (binanceProvider.supports(canonical)) {
            return binanceProvider;
        }
        if (alpacaProvider.supports(canonical)) {
            return alpacaProvider;
        }
        if (twelveDataProvider.supports(canonical)) {
            return twelveDataProvider;
        }
        if (alphaVantageProvider.supports(canonical)) {
            return alphaVantageProvider;
        }

        throw new UnsupportedSymbolException("Không tìm thấy nhà cung cấp cố định cho mã: " + canonical);
    }

    public String resolveProviderName(String symbol) {
        return getProvider(symbol).providerName();
    }
}
