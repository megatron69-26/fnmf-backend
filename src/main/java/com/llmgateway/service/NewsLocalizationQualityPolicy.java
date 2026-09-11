package com.llmgateway.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production policy kiểm tra chất lượng bản địa hóa tin tức tiếng Việt:
 * - displayTitleVi không rỗng;
 * - không giống originalTitle nếu originalTitle rõ ràng là tiếng Anh;
 * - có dấu hiệu là câu tiếng Việt hợp lệ;
 * - không pha trộn nhiều từ tiếng Anh chưa dịch (loại bỏ câu lai);
 * - 2–4 bullet tiếng Việt độc lập;
 * - bullet không lặp tiêu đề và không chứa boilerplate;
 * - publisher là metadata tùy chọn (nếu có thì không được là generic placeholder).
 */
public class NewsLocalizationQualityPolicy {

    private static final Pattern VIETNAMESE_DIACRITICS = Pattern.compile(
            "[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐÃ]"
    );

    private static final Pattern VIETNAMESE_FINANCIAL_TOKENS = Pattern.compile(
            "\\b(cổ phiếu|thị trường|chứng khoán|doanh thu|lợi nhuận|nhà đầu tư|tăng trưởng|hạ lãi suất|tăng lãi suất|lạm phát|suy thoái|giảm mạnh|tăng mạnh|tín hiệu|kỷ lục|dự báo|giá vàng|tiền điện tử|mở vị thế|nắm giữ|đầu tư|giải ngân|mua lại|sáp nhập|quỹ đầu tư|phiên giao dịch)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TICKER_OR_ACRONYM = Pattern.compile("^[A-Z0-9]{2,6}$");

    private static final Set<String> ALLOWED_PROPER_NAMES = new HashSet<>(Arrays.asList(
            "nvidia", "apple", "microsoft", "tesla", "google", "meta", "amazon", "intel", "amd",
            "qualcomm", "broadcom", "netflix", "openai", "vinfast", "vingroup", "warren", "buffett",
            "elon", "musk", "tim", "cook", "jensen", "huang", "powell", "jerome",
            "bitcoin", "ethereum", "binance", "coinbase", "tether", "solana", "ripple",
            "vnpay", "vietcombank", "techcombank", "mbbank", "fpt", "viettel",
            "wall", "street", "nasdaq", "dow", "jones", "sp500", "s&p"
    ));

    private static final Set<String> ALLOWED_BUSINESS_TERMS = new HashSet<>(Arrays.asList(
            "btc", "eth", "usd", "vnd", "ai", "etf", "vnpay", "fed", "ceo", "cfo", "ipo", "gdp", "cpi", "sec", "fomc",
            "million", "billion", "trillion", "m", "b", "k"
    ));

    private static final Set<String> COMMON_ENGLISH_WORDS = new HashSet<>(Arrays.asList(
            "report", "reports", "reported", "reporting",
            "quarterly", "quarter", "annual", "yearly",
            "revenue", "revenues", "earnings", "growth",
            "shares", "share", "stock", "stocks",
            "market", "markets", "price", "prices",
            "target", "targets", "upgrade", "downgrade", "upgrades", "downgrades",
            "investors", "investor", "trading", "trade", "trader", "traders",
            "rates", "rate", "interest", "inflation",
            "cuts", "cut", "hikes", "hike", "surges", "surge", "surged",
            "falls", "fall", "fell", "drops", "drop", "dropped",
            "rises", "rise", "rose", "risen", "jumps", "jump", "jumped",
            "analysts", "analyst", "beat", "beats", "miss", "misses",
            "guidance", "outlook", "sales", "sale",
            "dividend", "dividends", "rally", "slump",
            "gain", "gains", "loss", "losses", "profit", "profits", "profitable",
            "record", "high", "highs", "low", "lows",
            "and", "the", "of", "to", "in", "is", "are", "for", "on", "with", "by", "at", "from", "this", "that",
            "financial", "news", "company", "companies", "business", "fund", "funds",
            "holding", "holdings", "holds", "held", "unchanged", "policy", "meeting",
            "says", "said", "amid", "after", "before", "over", "under", "into", "about", "new",
            "crypto", "cryptocurrency", "blockchain",
            "etfs", "percent", "percentage"
    ));

    public static boolean hasExcessiveEnglishTokens(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String cleaned = text.replaceAll("[.,:;!?\"'()\\[\\]{}/*\\-–—]", " ");
        String[] tokens = cleaned.split("\\s+");

        int englishWordCount = 0;
        int meaningfulTokens = 0;

        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty()) continue;

            // Bỏ qua số và token chứa chữ số (ví dụ: 500, 100%, 2026, Q3)
            if (token.matches(".*\\d.*")) continue;

            // Bỏ qua ticker/viết hoa (ví dụ: NVDA, AAPL, BTC, USD)
            if (TICKER_OR_ACRONYM.matcher(token).matches()) continue;

            String lower = token.toLowerCase(Locale.ROOT);

            // Bỏ qua allowlist tên riêng / nghiệp vụ
            if (ALLOWED_PROPER_NAMES.contains(lower) || ALLOWED_BUSINESS_TERMS.contains(lower)) {
                continue;
            }

            meaningfulTokens++;

            if (COMMON_ENGLISH_WORDS.contains(lower)) {
                englishWordCount++;
            }
        }

        // Nếu có >= 2 từ tiếng Anh thông dụng -> coi là pha tạp tiếng Anh chưa dịch
        if (englishWordCount >= 2) {
            return true;
        }

        // Nếu tỷ lệ từ tiếng Anh chiếm >= 40% số từ có ý nghĩa
        if (meaningfulTokens > 0 && ((double) englishWordCount / meaningfulTokens) >= 0.40) {
            return true;
        }

        return false;
    }

    public static boolean hasVietnameseCharacteristics(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String clean = text.trim();
        if (VIETNAMESE_DIACRITICS.matcher(clean).find()) {
            return true;
        }
        return VIETNAMESE_FINANCIAL_TOKENS.matcher(clean).find();
    }

    public static boolean isLikelyEnglish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (hasExcessiveEnglishTokens(text)) {
            return true;
        }
        if (VIETNAMESE_DIACRITICS.matcher(text).find()) {
            return false;
        }
        return hasExcessiveEnglishTokens(text) || text.matches(".*\\b(the|and|of|to|in|is|for|on|with|by|at|from|stock|stocks|shares|market|report|earnings|quarter|revenue|price)\\b.*");
    }

    public static boolean isValidDisplayTitleVi(String displayTitleVi, String originalTitle) {
        if (displayTitleVi == null || displayTitleVi.isBlank()) {
            return false;
        }
        String trimmedDisplay = displayTitleVi.trim();
        if (!hasVietnameseCharacteristics(trimmedDisplay)) {
            return false;
        }
        if (hasExcessiveEnglishTokens(trimmedDisplay)) {
            return false;
        }
        if (originalTitle != null && !originalTitle.isBlank()) {
            String trimmedOrig = originalTitle.trim();
            if (trimmedDisplay.equalsIgnoreCase(trimmedOrig)) {
                return false;
            }
            if (isLikelyEnglish(trimmedOrig) && trimmedDisplay.equalsIgnoreCase(trimmedOrig)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidDisplaySummaryVi(String displaySummaryVi, String originalSummary, String displayTitleVi) {
        if (displaySummaryVi == null || displaySummaryVi.isBlank()) {
            return false;
        }
        String trimmedSummary = displaySummaryVi.trim();
        if (!hasVietnameseCharacteristics(trimmedSummary)) {
            return false;
        }
        if (hasExcessiveEnglishTokens(trimmedSummary)) {
            return false;
        }
        if (originalSummary != null && !originalSummary.isBlank()) {
            String trimmedOrig = originalSummary.trim();
            if (trimmedSummary.equalsIgnoreCase(trimmedOrig)) {
                return false;
            }
            if (isLikelyEnglish(trimmedOrig) && trimmedSummary.equalsIgnoreCase(trimmedOrig)) {
                return false;
            }
        }
        if (displayTitleVi != null && !displayTitleVi.isBlank()) {
            if (trimmedSummary.equalsIgnoreCase(displayTitleVi.trim())) {
                return false;
            }
        }
        if (NewsSummaryQualityPolicy.isBoilerplate(trimmedSummary)) {
            return false;
        }
        return true;
    }

    private static String stripLeadingBullet(String text) {
        String s = text.trim();
        while (s.startsWith("•") || s.startsWith("-") || s.startsWith("*") || s.startsWith("–")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    public static boolean isValidBullets(List<String> bullets, String title) {
        if (bullets == null || bullets.size() < 2 || bullets.size() > 4) {
            return false;
        }
        for (String b : bullets) {
            if (b == null || b.isBlank()) return false;
            String cleanB = stripLeadingBullet(b);
            if (!hasVietnameseCharacteristics(cleanB)) return false;
            if (hasExcessiveEnglishTokens(cleanB)) return false;
            if (title != null && cleanB.equalsIgnoreCase(title.trim())) return false;
            if (NewsSummaryQualityPolicy.isBoilerplate(cleanB)) return false;
        }
        return true;
    }

    public static boolean isFullyLocalized(String displayTitleVi, String originalTitle, List<String> bullets) {
        return isValidDisplayTitleVi(displayTitleVi, originalTitle) && isValidBullets(bullets, displayTitleVi);
    }

    public static boolean isFullyLocalized(
            String displayTitleVi,
            String originalTitle,
            String displaySummaryVi,
            String originalSummary,
            List<String> bullets
    ) {
        return isFullyLocalized(displayTitleVi, originalTitle, displaySummaryVi, originalSummary, bullets, null);
    }

    public static boolean isFullyLocalized(
            String displayTitleVi,
            String originalTitle,
            String displaySummaryVi,
            String originalSummary,
            List<String> bullets,
            String publisher
    ) {
        if (!isValidDisplayTitleVi(displayTitleVi, originalTitle)) {
            return false;
        }
        if (!isValidDisplaySummaryVi(displaySummaryVi, originalSummary, displayTitleVi)) {
            return false;
        }
        if (!isValidBullets(bullets, displayTitleVi)) {
            return false;
        }
        if (originalTitle == null || originalTitle.isBlank()) {
            return false;
        }
        if (originalSummary == null || originalSummary.isBlank()) {
            return false;
        }
        // Publisher là tùy chọn: cho phép null hoặc rỗng.
        // Nếu có giá trị thì phải không được là generic placeholder.
        if (publisher != null && !publisher.isBlank() && NewsPublisherResolver.isGeneric(publisher)) {
            return false;
        }
        return true;
    }
}
