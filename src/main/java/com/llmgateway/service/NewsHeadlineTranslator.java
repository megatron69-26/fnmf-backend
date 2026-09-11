package com.llmgateway.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ dịch tiêu đề tài chính sang tiếng Việt tự nhiên (Financial Headline Translator).
 * Dùng cho chế độ dự phòng Heuristics và làm giàu cache cũ khi không gọi Gemini AI.
 * TUYỆT ĐỐI KHÔNG dịch:
 * - Tên riêng công ty (Apple, Microsoft, Tesla, Nvidia, Intel, AMD, NetApp...)
 * - Mã cổ phiếu / crypto (NVDA, TSLA, BTC, ETH, AMAT, IRM, CRM, EQIX, SMCI...)
 * - Tên người (Warren Buffett, Jensen Huang, Elon Musk, Jim Cramer...)
 * - Thương hiệu tòa soạn (MarketBeat, CNBC, Yahoo Finance...)
 * - Con số và đơn vị tiền tệ ($410 Million, 35%, Q1, Q3...)
 */
public class NewsHeadlineTranslator {

    public static String translateHeadline(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String t = title.trim();

        // 1. Dạng: "... Takes $... Position in [Company] $[Ticker]"
        Pattern p1 = Pattern.compile("(?i)^(.+?)\\s+Takes\\s+(\\$[\\d.]+\\s*(?:Billion|Million|B|M)?)\\s+Position\\s+in\\s+(.+)$");
        Matcher m1 = p1.matcher(t);
        if (m1.matches()) {
            return m1.group(1).trim() + " mở vị thế " + m1.group(2).trim() + " vào " + m1.group(3).trim();
        }

        // 2. Dạng: "... Has $... Position in [Company] $[Ticker]"
        Pattern p2 = Pattern.compile("(?i)^(.+?)\\s+Has\\s+(\\$[\\d.]+\\s*(?:Billion|Million|B|M)?)\\s+(?:Stock\\s+)?Position\\s+in\\s+(.+)$");
        Matcher m2 = p2.matcher(t);
        if (m2.matches()) {
            return m2.group(1).trim() + " nắm giữ vị thế " + m2.group(2).trim() + " tại " + m2.group(3).trim();
        }

        // 3. Dạng: "... Invests $... in [Company] $[Ticker]"
        Pattern p3 = Pattern.compile("(?i)^(.+?)\\s+Invests\\s+(\\$[\\d.]+\\s*(?:Billion|Million|B|M)?)\\s+in\\s+(.+)$");
        Matcher m3 = p3.matcher(t);
        if (m3.matches()) {
            return m3.group(1).trim() + " đầu tư " + m3.group(2).trim() + " vào " + m3.group(3).trim();
        }

        // 4. Dạng: "... Makes New $... Investment in [Company] $[Ticker]"
        Pattern p4 = Pattern.compile("(?i)^(.+?)\\s+Makes\\s+New\\s+(\\$[\\d.]+\\s*(?:Billion|Million|B|M)?)\\s+Investment\\s+in\\s+(.+)$");
        Matcher m4 = p4.matcher(t);
        if (m4.matches()) {
            return m4.group(1).trim() + " giải ngân khoản đầu tư mới " + m4.group(2).trim() + " vào " + m4.group(3).trim();
        }

        // 5. Dạng: "... Raises Stake in [Company] $[Ticker]"
        Pattern p5 = Pattern.compile("(?i)^(.+?)\\s+Raises\\s+Stake\\s+in\\s+(.+)$");
        Matcher m5 = p5.matcher(t);
        if (m5.matches()) {
            return m5.group(1).trim() + " tăng tỷ lệ sở hữu tại " + m5.group(2).trim();
        }

        // 6. Dạng: "... Lowers Stake in [Company] $[Ticker]" or "Lowers Stock Position in"
        Pattern p6 = Pattern.compile("(?i)^(.+?)\\s+(?:Lowers|Reduces)\\s+(?:Stock\\s+Position|Stake)\\s+in\\s+(.+)$");
        Matcher m6 = p6.matcher(t);
        if (m6.matches()) {
            return m6.group(1).trim() + " giảm tỷ lệ sở hữu tại " + m6.group(2).trim();
        }

        // 7. Dạng: "... Purchases New Shares in [Company]" or "Purchases New Holdings in"
        Pattern p7 = Pattern.compile("(?i)^(.+?)\\s+Purchases\\s+New\\s+(?:Shares|Holdings|Position)\\s+in\\s+(.+)$");
        Matcher m7 = p7.matcher(t);
        if (m7.matches()) {
            return m7.group(1).trim() + " mua thêm cổ phần mới tại " + m7.group(2).trim();
        }

        // 8. Dạng: "... Acquires New Shares in [Company]"
        Pattern p8 = Pattern.compile("(?i)^(.+?)\\s+Acquires\\s+New\\s+Shares\\s+in\\s+(.+)$");
        Matcher m8 = p8.matcher(t);
        if (m8.matches()) {
            return m8.group(1).trim() + " mua lượng lớn cổ phần mới của " + m8.group(2).trim();
        }

        // 9. Dạng: "[Company] Board Member Sells $... of Company Stock"
        Pattern p9 = Pattern.compile("(?i)^(.+?)\\s+Board\\s+Member\\s+Sells\\s+(\\$[\\d.]+\\s*(?:Billion|Million|B|M)?)\\s+of\\s+Company\\s+Stock$");
        Matcher m9 = p9.matcher(t);
        if (m9.matches()) {
            return "Thành viên HĐQT " + m9.group(1).trim() + " bán " + m9.group(2).trim() + " cổ phiếu công ty";
        }

        // 10. Dạng: "[Company] Stock Gains on [Reason]"
        Pattern p10 = Pattern.compile("(?i)^(.+?)\\s+Stock\\s+Gains\\s+on\\s+(.+)$");
        Matcher m10 = p10.matcher(t);
        if (m10.matches()) {
            return "Cổ phiếu " + m10.group(1).trim() + " tăng trưởng nhờ " + m10.group(2).trim();
        }

        // 11. Dạng: "... Surges on [Reason]" or "Surges Past ..."
        Pattern p11 = Pattern.compile("(?i)^(.+?)\\s+Surges\\s+Past\\s+(.+)$");
        Matcher m11 = p11.matcher(t);
        if (m11.matches()) {
            return m11.group(1).trim() + " bứt phá vượt mốc " + m11.group(2).trim();
        }

        Pattern p12 = Pattern.compile("(?i)^(.+?)\\s+Surges\\s+on\\s+(.+)$");
        Matcher m12 = p12.matcher(t);
        if (m12.matches()) {
            return m12.group(1).trim() + " tăng mạnh nhờ " + m12.group(2).trim();
        }

        // 12. Dạng: "... Reports Record Q... Revenue"
        Pattern p13 = Pattern.compile("(?i)^(.+?)\\s+Reports\\s+Record\\s+(Q\\d+)\\s+Revenue$");
        Matcher m13 = p13.matcher(t);
        if (m13.matches()) {
            return m13.group(1).trim() + " công bố doanh thu kỷ lục " + m13.group(2).trim();
        }

        // Nếu tiêu đề đã có dấu hiệu tiếng Việt thực chất, giữ nguyên
        if (NewsLocalizationQualityPolicy.hasVietnameseCharacteristics(t)) {
            return t;
        }

        // Tuyệt đối không coi kết quả trả nguyên văn tiếng Anh là bản dịch
        return null;
    }

    public static java.util.Optional<String> tryTranslate(String title) {
        String translated = translateHeadline(title);
        if (translated != null && NewsLocalizationQualityPolicy.isValidDisplayTitleVi(translated, title)) {
            return java.util.Optional.of(translated);
        }
        return java.util.Optional.empty();
    }
}
