package com.llmgateway.service.provider;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.exception.UnsupportedSymbolException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class GeminiShardRouter {

    public static final String SHARD_1 = "GEMINI_SHARD_1";
    public static final String SHARD_2 = "GEMINI_SHARD_2";
    public static final String SHARD_3 = "GEMINI_SHARD_3";

    private static final Set<String> SHARD_1_SYMBOLS = Set.of(
            MarketSymbolConfig.CANONICAL_BTC,
            MarketSymbolConfig.CANONICAL_ETH,
            MarketSymbolConfig.CANONICAL_XAU
    );

    private static final Set<String> SHARD_2_SYMBOLS = Set.of(
            MarketSymbolConfig.CANONICAL_AAPL,
            MarketSymbolConfig.CANONICAL_MSFT,
            MarketSymbolConfig.CANONICAL_NVDA,
            MarketSymbolConfig.CANONICAL_GOOGL
    );

    private static final Set<String> SHARD_3_SYMBOLS = Set.of(
            MarketSymbolConfig.CANONICAL_TSLA,
            MarketSymbolConfig.CANONICAL_AMZN,
            MarketSymbolConfig.CANONICAL_META,
            MarketSymbolConfig.CANONICAL_JPM
    );

    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String shard1Key;

    @Value("${gemini.api.key-shard-2:${GEMINI_API_KEY_SHARD_2:}}")
    private String shard2Key;

    @Value("${gemini.api.key-shard-3:${GEMINI_API_KEY_SHARD_3:}}")
    private String shard3Key;

    public void setShardKeys(String k1, String k2, String k3) {
        this.shard1Key = k1;
        this.shard2Key = k2;
        this.shard3Key = k3;
    }

    public record GeminiShardInfo(
            String shardName,
            String apiKey
    ) {}

    public GeminiShardInfo resolveShard(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Mã tài sản không được để trống khi định tuyến Gemini shard");
        }

        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);

        if (SHARD_1_SYMBOLS.contains(canonical)) {
            validateKey(shard1Key, SHARD_1, canonical);
            return new GeminiShardInfo(SHARD_1, shard1Key.trim());
        }

        if (SHARD_2_SYMBOLS.contains(canonical)) {
            validateKey(shard2Key, SHARD_2, canonical);
            return new GeminiShardInfo(SHARD_2, shard2Key.trim());
        }

        if (SHARD_3_SYMBOLS.contains(canonical)) {
            validateKey(shard3Key, SHARD_3, canonical);
            return new GeminiShardInfo(SHARD_3, shard3Key.trim());
        }

        throw new UnsupportedSymbolException("Không tìm thấy Gemini shard cho mã: " + canonical);
    }

    public String resolveShardName(String symbol) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        if (SHARD_1_SYMBOLS.contains(canonical)) return SHARD_1;
        if (SHARD_2_SYMBOLS.contains(canonical)) return SHARD_2;
        if (SHARD_3_SYMBOLS.contains(canonical)) return SHARD_3;
        throw new UnsupportedSymbolException("Không tìm thấy Gemini shard cho mã: " + canonical);
    }

    private void validateKey(String key, String shardName, String symbol) {
        if (key == null || key.isBlank() || key.startsWith("${")) {
            throw new ForecastUnavailableException("Chưa cấu hình API key cho " + shardName + " (mã: " + symbol + ")");
        }
    }
}
