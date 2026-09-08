package com.llmgateway.config;

import com.llmgateway.exception.UnsupportedSymbolException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cấu hình danh mục mã tài sản thị trường chuẩn hóa (Canonical Symbol Registry).
 * Chỉ hỗ trợ các mã có nguồn dữ liệu thời gian thực xác thực:
 * - BTCUSDT (Bitcoin - Crypto)
 * - ETHUSDT (Ethereum - Crypto)
 * - XAUUSD (Vàng giao ngay - Commodity, tham chiếu trực tiếp qua PAXGUSDT thực của Binance)
 *
 * TUYỆT ĐỐI KHÔNG HỖ TRỢ USOIL / CL hay các mã không có nguồn stream khả thi:
 * Không gán USOIL vào BTC hay sinh giá giả dưới mọi hình thức!
 */
public final class MarketSymbolConfig {

    public record SymbolMeta(
            String canonicalSymbol,
            String name,
            String category,
            String binanceSymbol,
            String referenceNote
    ) {}

    public static final String CANONICAL_BTC = "BTCUSDT";
    public static final String CANONICAL_ETH = "ETHUSDT";
    public static final String CANONICAL_XAU = "XAUUSD";

    private static final Map<String, SymbolMeta> CANONICAL_REGISTRY;
    private static final Map<String, String> ALIAS_MAP;

    static {
        Map<String, SymbolMeta> reg = new LinkedHashMap<>();
        reg.put(CANONICAL_BTC, new SymbolMeta(
                CANONICAL_BTC,
                "Bitcoin",
                "CRYPTO",
                "BTCUSDT",
                "Binance Spot BTC/USDT"
        ));
        reg.put(CANONICAL_ETH, new SymbolMeta(
                CANONICAL_ETH,
                "Ethereum",
                "CRYPTO",
                "ETHUSDT",
                "Binance Spot ETH/USDT"
        ));
        reg.put(CANONICAL_XAU, new SymbolMeta(
                CANONICAL_XAU,
                "Vàng (Gold Spot)",
                "COMMODITY",
                "PAXGUSDT",
                "Binance PAXG/USDT (Gold-backed real spot commodity reference)"
        ));
        CANONICAL_REGISTRY = Collections.unmodifiableMap(reg);

        Map<String, String> aliases = new LinkedHashMap<>();
        // BTC aliases
        aliases.put("BTC", CANONICAL_BTC);
        aliases.put("BTCUSDT", CANONICAL_BTC);
        aliases.put("BTC/USDT", CANONICAL_BTC);
        aliases.put("BTCUSD", CANONICAL_BTC);

        // ETH aliases
        aliases.put("ETH", CANONICAL_ETH);
        aliases.put("ETHUSDT", CANONICAL_ETH);
        aliases.put("ETH/USDT", CANONICAL_ETH);
        aliases.put("ETHUSD", CANONICAL_ETH);

        // XAU aliases
        aliases.put("XAU", CANONICAL_XAU);
        aliases.put("XAUUSD", CANONICAL_XAU);
        aliases.put("XAU/USD", CANONICAL_XAU);
        aliases.put("PAXG", CANONICAL_XAU);
        aliases.put("PAXGUSDT", CANONICAL_XAU);
        aliases.put("GOLD", CANONICAL_XAU);

        ALIAS_MAP = Collections.unmodifiableMap(aliases);
    }

    private MarketSymbolConfig() {
    }

    public static boolean isSupported(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return false;
        }
        String clean = normalizeKey(symbol);
        return ALIAS_MAP.containsKey(clean);
    }

    public static void validateSupported(String symbol) {
        if (!isSupported(symbol)) {
            throw new UnsupportedSymbolException("Mã tài sản chưa được hỗ trợ: " + (symbol != null ? symbol.trim() : "null"));
        }
    }

    public static String getCanonicalSymbol(String symbol) {
        validateSupported(symbol);
        return ALIAS_MAP.get(normalizeKey(symbol));
    }

    public static SymbolMeta getMeta(String canonicalSymbol) {
        SymbolMeta meta = CANONICAL_REGISTRY.get(canonicalSymbol);
        if (meta == null) {
            throw new UnsupportedSymbolException("Mã tài sản chuẩn không tồn tại: " + canonicalSymbol);
        }
        return meta;
    }

    public static String getBinanceSymbol(String canonicalSymbol) {
        return getMeta(canonicalSymbol).binanceSymbol();
    }

    public static String getDisplayName(String symbol) {
        if (!isSupported(symbol)) {
            return symbol;
        }
        String canonical = getCanonicalSymbol(symbol);
        SymbolMeta meta = CANONICAL_REGISTRY.get(canonical);
        return meta != null ? meta.name() : symbol;
    }

    public static List<SymbolMeta> getAllCanonical() {
        return List.copyOf(CANONICAL_REGISTRY.values());
    }

    private static String normalizeKey(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
