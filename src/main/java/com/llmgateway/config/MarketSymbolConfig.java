package com.llmgateway.config;

import com.llmgateway.dto.stock.StockCatalogDto;
import com.llmgateway.exception.UnsupportedSymbolException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cấu hình danh mục mã tài sản thị trường chuẩn hóa (Canonical Symbol Registry).
 * Hỗ trợ các mã có nguồn dữ liệu thời gian thực và dữ liệu cổ phiếu thực tế:
 * - BTCUSDT (Bitcoin - Crypto)
 * - ETHUSDT (Ethereum - Crypto)
 * - XAUUSD (Vàng giao ngay - Commodity, tham chiếu trực tiếp qua PAXGUSDT thực của Binance)
 * - 8 Cổ phiếu hàng đầu (US Stocks qua Alpha Vantage):
 *   AAPL, MSFT, NVDA, TSLA, AMZN, META, GOOGL, JPM
 *
 * TUYỆT ĐỐI KHÔNG HỖ TRỢ USOIL / CL hay các mã không có nguồn dữ liệu xác thực:
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

    // 8 cổ phiếu hỗ trợ chính thức
    public static final String CANONICAL_AAPL = "AAPL";
    public static final String CANONICAL_MSFT = "MSFT";
    public static final String CANONICAL_NVDA = "NVDA";
    public static final String CANONICAL_TSLA = "TSLA";
    public static final String CANONICAL_AMZN = "AMZN";
    public static final String CANONICAL_META = "META";
    public static final String CANONICAL_GOOGL = "GOOGL";
    public static final String CANONICAL_JPM = "JPM";

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

        // 8 Cổ phiếu Mỹ
        reg.put(CANONICAL_AAPL, new SymbolMeta(
                CANONICAL_AAPL,
                "Apple Inc.",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_MSFT, new SymbolMeta(
                CANONICAL_MSFT,
                "Microsoft Corporation",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_NVDA, new SymbolMeta(
                CANONICAL_NVDA,
                "NVIDIA Corporation",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_TSLA, new SymbolMeta(
                CANONICAL_TSLA,
                "Tesla, Inc.",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_AMZN, new SymbolMeta(
                CANONICAL_AMZN,
                "Amazon.com, Inc.",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_META, new SymbolMeta(
                CANONICAL_META,
                "Meta Platforms, Inc.",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_GOOGL, new SymbolMeta(
                CANONICAL_GOOGL,
                "Alphabet Inc. (Google)",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
        ));
        reg.put(CANONICAL_JPM, new SymbolMeta(
                CANONICAL_JPM,
                "JPMorgan Chase & Co.",
                "STOCK",
                null,
                "Alpha Vantage TIME_SERIES_DAILY"
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

        // Stock aliases
        aliases.put("AAPL", CANONICAL_AAPL);
        aliases.put("MSFT", CANONICAL_MSFT);
        aliases.put("NVDA", CANONICAL_NVDA);
        aliases.put("TSLA", CANONICAL_TSLA);
        aliases.put("AMZN", CANONICAL_AMZN);
        aliases.put("META", CANONICAL_META);
        aliases.put("GOOGL", CANONICAL_GOOGL);
        aliases.put("GOOG", CANONICAL_GOOGL);
        aliases.put("JPM", CANONICAL_JPM);

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

    public static boolean isStock(String symbol) {
        if (!isSupported(symbol)) {
            return false;
        }
        String canonical = getCanonicalSymbol(symbol);
        SymbolMeta meta = CANONICAL_REGISTRY.get(canonical);
        return meta != null && "STOCK".equalsIgnoreCase(meta.category());
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
        String binanceSym = getMeta(canonicalSymbol).binanceSymbol();
        if (binanceSym == null) {
            throw new UnsupportedSymbolException("Mã " + canonicalSymbol + " không sử dụng Binance provider");
        }
        return binanceSym;
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

    public static List<SymbolMeta> getCryptoAndCommoditySymbols() {
        List<SymbolMeta> list = new ArrayList<>();
        for (SymbolMeta meta : CANONICAL_REGISTRY.values()) {
            if (!"STOCK".equalsIgnoreCase(meta.category())) {
                list.add(meta);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public static List<SymbolMeta> getStockSymbols() {
        List<SymbolMeta> list = new ArrayList<>();
        for (SymbolMeta meta : CANONICAL_REGISTRY.values()) {
            if ("STOCK".equalsIgnoreCase(meta.category())) {
                list.add(meta);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public static List<StockCatalogDto> getStockCatalog() {
        List<StockCatalogDto> catalog = new ArrayList<>();
        for (SymbolMeta meta : getStockSymbols()) {
            catalog.add(new StockCatalogDto(meta.canonicalSymbol(), meta.name()));
        }
        return Collections.unmodifiableList(catalog);
    }

    private static String normalizeKey(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
