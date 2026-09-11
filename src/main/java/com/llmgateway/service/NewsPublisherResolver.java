package com.llmgateway.service;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bộ chuẩn hóa nhà xuất bản tin tức tài chính (Accurate Publisher Resolution).
 * - Tận dụng trường `source` từ Alpha Vantage API.
 * - Nếu source rỗng hoặc generic, trích xuất chuẩn từ hostname của `articleUrl`.
 * - Tuyệt đối không trả về chuỗi vô nghĩa như "Financial News", "Tin thị trường", "Unknown".
 * - Phân biệt rạch ròi giữa Publisher (nhà xuất bản) và Author (tác giả).
 */
public class NewsPublisherResolver {

    private static final Set<String> GENERIC_NAMES = Set.of(
            "financial news",
            "tin thị trường",
            "unknown",
            "tổng hợp",
            "market news",
            "n/a",
            "none",
            "null",
            "market",
            "general"
    );

    private static final Map<String, String> DOMAIN_TO_PUBLISHER = Map.ofEntries(
            Map.entry("marketbeat.com", "MarketBeat"),
            Map.entry("finance.yahoo.com", "Yahoo Finance"),
            Map.entry("uk.finance.yahoo.com", "Yahoo Finance"),
            Map.entry("ca.finance.yahoo.com", "Yahoo Finance"),
            Map.entry("sg.finance.yahoo.com", "Yahoo Finance"),
            Map.entry("au.finance.yahoo.com", "Yahoo Finance"),
            Map.entry("att.yahoo.com", "Yahoo Finance"),
            Map.entry("yahoo.com", "Yahoo Finance"),
            Map.entry("cnbc.com", "CNBC"),
            Map.entry("tipranks.com", "TipRanks"),
            Map.entry("247wallst.com", "24/7 Wall St."),
            Map.entry("globes.co.il", "Globes"),
            Map.entry("reuters.com", "Reuters"),
            Map.entry("bloomberg.com", "Bloomberg"),
            Map.entry("coindesk.com", "CoinDesk"),
            Map.entry("cointelegraph.com", "CoinTelegraph"),
            Map.entry("benzinga.com", "Benzinga"),
            Map.entry("fool.com", "The Motley Fool"),
            Map.entry("investors.com", "Investor's Business Daily"),
            Map.entry("wsj.com", "The Wall Street Journal"),
            Map.entry("barrons.com", "Barron's"),
            Map.entry("forbes.com", "Forbes"),
            Map.entry("ft.com", "Financial Times"),
            Map.entry("thestreet.com", "TheStreet"),
            Map.entry("marketwatch.com", "MarketWatch"),
            Map.entry("seekingalpha.com", "Seeking Alpha"),
            Map.entry("investing.com", "Investing.com"),
            Map.entry("cryptoslate.com", "CryptoSlate"),
            Map.entry("decrypt.co", "Decrypt"),
            Map.entry("businessinsider.com", "Business Insider"),
            Map.entry("fortune.com", "Fortune"),
            Map.entry("zacks.com", "Zacks Investment Research"),
            Map.entry("fxstreet.com", "FXStreet"),
            Map.entry("coinmarketcap.com", "CoinMarketCap"),
            Map.entry("dailyfx.com", "DailyFX"),
            Map.entry("ad-hoc-news.de", "Ad-hoc-news"),
            Map.entry("mexc.com", "MEXC"),
            Map.entry("pulse.mk.co.kr", "Pulse News Korea")
    );

    public static boolean isGeneric(String source) {
        if (source == null || source.isBlank()) return true;
        return GENERIC_NAMES.contains(source.trim().toLowerCase(Locale.ROOT));
    }

    public static String resolvePublisher(String rawSource, String articleUrl) {
        // 1. Kiểm tra rawSource trước nếu đã có giá trị tốt không phải generic
        if (rawSource != null && !rawSource.isBlank() && !isGeneric(rawSource)) {
            String trimmed = rawSource.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : DOMAIN_TO_PUBLISHER.entrySet()) {
                if (lower.contains(entry.getKey()) || lower.equals(entry.getValue().toLowerCase(Locale.ROOT))) {
                    return entry.getValue();
                }
            }
            return trimmed;
        }

        // 2. Nếu rawSource rỗng hoặc thuộc nhóm generic, phân tích từ hostname URL bài báo
        if (articleUrl != null && !articleUrl.isBlank()) {
            try {
                URI uri = URI.create(articleUrl.trim());
                String host = uri.getHost();
                if (host != null) {
                    host = host.toLowerCase(Locale.ROOT);
                    if (host.startsWith("www.")) {
                        host = host.substring(4);
                    }
                    if (DOMAIN_TO_PUBLISHER.containsKey(host)) {
                        return DOMAIN_TO_PUBLISHER.get(host);
                    }
                    // Kiểm tra đuôi domain
                    for (Map.Entry<String, String> entry : DOMAIN_TO_PUBLISHER.entrySet()) {
                        if (host.endsWith(entry.getKey())) {
                            return entry.getValue();
                        }
                    }
                    // Chưa có mapping: dùng hostname đã làm sạch (bỏ www.)
                    return host;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Nếu URL không hợp lệ hoặc không có hostname: trả null/rỗng
        // Tuyệt đối không fallback về MarketBeat, Financial News, Tin thị trường hoặc Unknown
        return null;
    }
}
