package com.llmgateway.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chính sách kiểm soát chất lượng nội dung tóm tắt tin tức.
 * Loại bỏ hoàn toàn các cụm từ khuôn mẫu (boilerplate):
 * - "Trọng tâm tin tức:"
 * - "Tác động thị trường:"
 * - "Khuyến nghị FNMF:"
 * - "Không có bản tóm tắt chi tiết."
 * Đảm bảo danh sách điểm chính luôn gồm 2 đến 4 ý thực chất từ nội dung bài báo.
 */
public class NewsSummaryQualityPolicy {

    private static final List<String> BOILERPLATE_PREFIXES = List.of(
            "trọng tâm tin tức:",
            "trọng tâm:",
            "tác động thị trường:",
            "khuyến nghị fnmf:",
            "khuyến nghị:",
            "bài viết này nói về:",
            "bài viết này nói về",
            "dưới đây là tóm tắt:",
            "dưới đây là tóm tắt",
            "dưới đây là các điểm chính:",
            "dưới đây là các điểm chính",
            "sau đây là tóm tắt:",
            "sau đây là tóm tắt",
            "không có bản tóm tắt chi tiết.",
            "không có bản tóm tắt chi tiết",
            "không có tóm tắt chi tiết.",
            "không có tóm tắt"
    );

    private static final Pattern SENTENCE_SPLIT_REGEX = Pattern.compile("(?<=[.!?])\\s+");

    public static boolean isBoilerplate(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.trim().toLowerCase();
        for (String prefix : BOILERPLATE_PREFIXES) {
            if (lower.startsWith(prefix) || lower.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasBoilerplateBullets(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) {
            return true;
        }
        for (String bullet : bullets) {
            if (isBoilerplate(bullet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Trích xuất 2 đến 4 câu chứa thông tin sự kiện thật từ bản tóm tắt gốc hoặc tiêu đề.
     * Không thêm văn phong nhận định đầu tư giả tạo.
     */
    public static List<String> extractFactualBullets(String title, String summary) {
        List<String> candidates = new ArrayList<>();

        if (summary != null && !summary.isBlank()) {
            String cleanSummary = summary.trim();
            String[] sentences = SENTENCE_SPLIT_REGEX.split(cleanSummary);
            for (String sentence : sentences) {
                String trimmed = cleanSentence(sentence);
                if (trimmed.length() >= 20 && !isBoilerplate(trimmed)) {
                    candidates.add(trimmed);
                }
            }
        }

        // Nếu summary không đủ tách câu (hoặc chỉ có 1 câu ngắn)
        if (candidates.size() < 2 && title != null && !title.isBlank()) {
            String cleanTitle = cleanSentence(title);
            if (!candidates.contains(cleanTitle) && cleanTitle.length() >= 15) {
                candidates.add(0, cleanTitle);
            }
        }

        // Nếu vẫn chỉ có 1 ý nhưng dài, thử tách theo dấu phẩy / chấm phẩy
        if (candidates.size() == 1 && candidates.get(0).length() > 90) {
            String single = candidates.get(0);
            String[] clauses = single.split("[,;]\\s+");
            if (clauses.length >= 2) {
                candidates.clear();
                for (String clause : clauses) {
                    String cl = cleanSentence(clause);
                    if (cl.length() >= 20) {
                        candidates.add(cl);
                    }
                }
            }
        }

        // Giới hạn tối thiểu 2 và tối đa 4 gạch đầu dòng
        if (candidates.isEmpty()) {
            String fallback = (title != null && !title.isBlank()) ? title.trim() : "Thông tin thị trường đang được cập nhật.";
            candidates.add(cleanSentence(fallback));
            candidates.add("Xem chi tiết thông tin đầy đủ tại bài báo gốc.");
        } else if (candidates.size() == 1) {
            candidates.add("Chi tiết diễn biến và số liệu được ghi nhận trong bài viết gốc.");
        }

        if (candidates.size() > 4) {
            return new ArrayList<>(candidates.subList(0, 4));
        }
        return candidates;
    }

    /**
     * Làm sạch và chuẩn hóa danh sách gạch đầu dòng.
     * Nếu danh sách hiện tại chứa boilerplate hoặc rỗng, thay thế bằng các câu thực chất từ originalSummary.
     */
    public static List<String> sanitizeBullets(List<String> rawBullets, String title, String originalSummary) {
        if (hasBoilerplateBullets(rawBullets)) {
            return extractFactualBullets(title, originalSummary);
        }

        List<String> cleaned = new ArrayList<>();
        for (String bullet : rawBullets) {
            String s = cleanSentence(bullet);
            if (!s.isBlank() && !isBoilerplate(s)) {
                cleaned.add(s);
            }
        }

        if (cleaned.size() < 2) {
            return extractFactualBullets(title, originalSummary);
        }

        if (cleaned.size() > 4) {
            return new ArrayList<>(cleaned.subList(0, 4));
        }

        return cleaned;
    }

    private static String cleanSentence(String text) {
        if (text == null) return "";
        String s = text.trim();
        while (s.startsWith("•") || s.startsWith("-") || s.startsWith("*") || s.startsWith("–")) {
            s = s.substring(1).trim();
        }
        return s;
    }
}
