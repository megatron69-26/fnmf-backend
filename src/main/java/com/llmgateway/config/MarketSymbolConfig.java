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

    // 5 cặp Binance mới thay thế cổ phiếu
    public static final String CANONICAL_BNB = "BNBUSDT";
    public static final String CANONICAL_SOL = "SOLUSDT";
    public static final String CANONICAL_XRP = "XRPUSDT";
    public static final String CANONICAL_ADA = "ADAUSDT";
    public static final String CANONICAL_DOGE = "DOGEUSDT";

    // 8 cổ phiếu cũ (ngừng giao dịch mới, giữ hằng số để tham chiếu lịch sử/audit)
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
        reg.put(CANONICAL_BNB, new SymbolMeta(
                CANONICAL_BNB,
                "BNB",
                "CRYPTO",
                "BNBUSDT",
                "Binance Spot BNB/USDT"
        ));
        reg.put(CANONICAL_SOL, new SymbolMeta(
                CANONICAL_SOL,
                "Solana",
                "CRYPTO",
                "SOLUSDT",
                "Binance Spot SOL/USDT"
        ));
        reg.put(CANONICAL_XRP, new SymbolMeta(
                CANONICAL_XRP,
                "XRP",
                "CRYPTO",
                "XRPUSDT",
                "Binance Spot XRP/USDT"
        ));
        reg.put(CANONICAL_ADA, new SymbolMeta(
                CANONICAL_ADA,
                "Cardano",
                "CRYPTO",
                "ADAUSDT",
                "Binance Spot ADA/USDT"
        ));
        reg.put(CANONICAL_DOGE, new SymbolMeta(
                CANONICAL_DOGE,
                "Dogecoin",
                "CRYPTO",
                "DOGEUSDT",
                "Binance Spot DOGE/USDT"
        ));

        CANONICAL_REGISTRY = Collections.unmodifiableMap(reg);

        Map<String, String> aliases = new LinkedHashMap<>();
        // BTC aliases
        aliases.put("BTC", CANONICAL_BTC);
        aliases.put("BTCUSDT", CANONICAL_BTC);
        aliases.put("BTCUSD", CANONICAL_BTC);

        // ETH aliases
        aliases.put("ETH", CANONICAL_ETH);
        aliases.put("ETHUSDT", CANONICAL_ETH);
        aliases.put("ETHUSD", CANONICAL_ETH);

        // XAU aliases
        aliases.put("XAU", CANONICAL_XAU);
        aliases.put("XAUUSD", CANONICAL_XAU);
        aliases.put("PAXG", CANONICAL_XAU);
        aliases.put("PAXGUSDT", CANONICAL_XAU);
        aliases.put("GOLD", CANONICAL_XAU);

        // BNB aliases
        aliases.put("BNB", CANONICAL_BNB);
        aliases.put("BNBUSDT", CANONICAL_BNB);
        aliases.put("BNBUSD", CANONICAL_BNB);

        // SOL aliases
        aliases.put("SOL", CANONICAL_SOL);
        aliases.put("SOLUSDT", CANONICAL_SOL);
        aliases.put("SOLUSD", CANONICAL_SOL);

        // XRP aliases
        aliases.put("XRP", CANONICAL_XRP);
        aliases.put("XRPUSDT", CANONICAL_XRP);
        aliases.put("XRPUSD", CANONICAL_XRP);

        // ADA aliases
        aliases.put("ADA", CANONICAL_ADA);
        aliases.put("ADAUSDT", CANONICAL_ADA);
        aliases.put("ADAUSD", CANONICAL_ADA);

        // DOGE aliases
        aliases.put("DOGE", CANONICAL_DOGE);
        aliases.put("DOGEUSDT", CANONICAL_DOGE);
        aliases.put("DOGEUSD", CANONICAL_DOGE);

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
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new UnsupportedSymbolException("Mã tài sản không được để trống");
        }
        String clean = normalizeKey(symbol);
        String canonical = ALIAS_MAP.get(clean);
        if (canonical != null) {
            return canonical;
        }
        return switch (clean) {
            case "AAPL" -> CANONICAL_AAPL;
            case "MSFT" -> CANONICAL_MSFT;
            case "NVDA" -> CANONICAL_NVDA;
            case "TSLA" -> CANONICAL_TSLA;
            case "AMZN" -> CANONICAL_AMZN;
            case "META" -> CANONICAL_META;
            case "GOOGL", "GOOG" -> CANONICAL_GOOGL;
            case "JPM" -> CANONICAL_JPM;
            default -> throw new UnsupportedSymbolException("Mã tài sản chưa được hỗ trợ: " + symbol);
        };
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
            if (symbol != null) {
                String clean = symbol.trim().toUpperCase(Locale.ROOT);
                return switch (clean) {
                    case "AAPL" -> "Apple Inc.";
                    case "MSFT" -> "Microsoft Corporation";
                    case "NVDA" -> "NVIDIA Corporation";
                    case "TSLA" -> "Tesla, Inc.";
                    case "AMZN" -> "Amazon.com, Inc.";
                    case "META" -> "Meta Platforms, Inc.";
                    case "GOOGL", "GOOG" -> "Alphabet Inc. (Google)";
                    case "JPM" -> "JPMorgan Chase & Co.";
                    default -> symbol;
                };
            }
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
        return List.copyOf(CANONICAL_REGISTRY.values());
    }

    public static List<SymbolMeta> getStockSymbols() {
        return Collections.emptyList();
    }

    public static List<StockCatalogDto> getStockCatalog() {
        return List.of(
                new StockCatalogDto(CANONICAL_BNB, "BNB"),
                new StockCatalogDto(CANONICAL_SOL, "Solana"),
                new StockCatalogDto(CANONICAL_XRP, "XRP"),
                new StockCatalogDto(CANONICAL_ADA, "Cardano"),
                new StockCatalogDto(CANONICAL_DOGE, "Dogecoin")
        );
    }

    private static String normalizeKey(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").replace("/", "");
    }
}
