package com.llmgateway;

import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.exception.ForecastUnavailableException;
import com.llmgateway.service.ForecastQualityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ForecastQualityPolicyTest {

    private ForecastResponse createValidForecast() {
        return new ForecastResponse(
                "BTCUSDT",
                "Bitcoin",
                new BigDecimal("65000.00"),
                "BULLISH_UPTREND",
                "24H_7D",
                new BigDecimal("63000.00"),
                new BigDecimal("68000.00"),
                "BUY",
                85,
                List.of(
                        "Dòng tiền tổ chức tiếp tục gia tăng tích lũy.",
                        "Ngưỡng hỗ trợ 63000 được giữ vững qua nhiều đợt kiểm định.",
                        "Chỉ báo động lượng xác nhận cấu trúc hồi phục lành mạnh."
                ),
                "Cấu trúc nến duy trì đà tăng trưởng trên các khung thời gian ngắn và trung hạn.",
                "Tâm lý thị trường ổn định, kỳ vọng vĩ mô hỗ trợ dòng tiền.",
                "GEMINI",
                30,
                false,
                LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("Bản dự báo hợp lệ đầy đủ các tiêu chuẩn phải vượt qua kiểm duyệt")
    void testValidForecastPasses() {
        ForecastResponse forecast = createValidForecast();
        assertTrue(ForecastQualityPolicy.isValid(forecast));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(forecast));
    }

    @Test
    @DisplayName("Khuyến nghị không thuộc tập hợp chuẩn (BUY_NOW, FAKE, null) bị từ chối")
    void testInvalidRecommendationRejected() {
        ForecastResponse f1 = createValidForecast();
        f1.setRecommendation("BUY_NOW");
        assertFalse(ForecastQualityPolicy.isValid(f1));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setRecommendation(null);
        assertFalse(ForecastQualityPolicy.isValid(f2));

        ForecastResponse f3 = createValidForecast();
        f3.setRecommendation("STRONG_BUY");
        assertTrue(ForecastQualityPolicy.isValid(f3));
    }

    @Test
    @DisplayName("Xu hướng trendPrediction bắt buộc thuộc 3 nhóm chuẩn")
    void testTrendPredictionValidation() {
        ForecastResponse f = createValidForecast();

        f.setTrendPrediction("BULLISH_UPTREND");
        assertTrue(ForecastQualityPolicy.isValid(f));

        f.setTrendPrediction("BEARISH_DOWNTREND");
        assertTrue(ForecastQualityPolicy.isValid(f));

        f.setTrendPrediction("SIDEWAYS_CONSOLIDATION");
        assertTrue(ForecastQualityPolicy.isValid(f));

        f.setTrendPrediction("BULLISH"); // Không đủ định dạng chuẩn
        assertFalse(ForecastQualityPolicy.isValid(f));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f));

        f.setTrendPrediction("RANDOM_TREND");
        assertFalse(ForecastQualityPolicy.isValid(f));

        f.setTrendPrediction(null);
        assertFalse(ForecastQualityPolicy.isValid(f));
    }

    @Test
    @DisplayName("Nguồn phân tích bắt buộc là GEMINI (từ chối HEURISTIC hoặc null)")
    void testAnalysisSourceValidation() {
        ForecastResponse f1 = createValidForecast();
        f1.setAnalysisSource("HEURISTIC");
        assertFalse(ForecastQualityPolicy.isValid(f1));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setAnalysisSource(null);
        assertFalse(ForecastQualityPolicy.isValid(f2));

        ForecastResponse f3 = createValidForecast();
        f3.setAnalysisSource("gemini"); // case-insensitive
        assertTrue(ForecastQualityPolicy.isValid(f3));
    }

    @Test
    @DisplayName("Số lượng nến candleCount phải trong phạm vi 1 đến 30")
    void testCandleCountValidation() {
        ForecastResponse f = createValidForecast();

        f.setCandleCount(0);
        assertFalse(ForecastQualityPolicy.isValid(f));

        f.setCandleCount(-5);
        assertFalse(ForecastQualityPolicy.isValid(f));

        f.setCandleCount(31);
        assertFalse(ForecastQualityPolicy.isValid(f));

        f.setCandleCount(null);
        assertFalse(ForecastQualityPolicy.isValid(f));

        f.setCandleCount(1);
        assertTrue(ForecastQualityPolicy.isValid(f));

        f.setCandleCount(30);
        assertTrue(ForecastQualityPolicy.isValid(f));
    }

    @Test
    @DisplayName("Mã tài sản hoặc giá hiện tại không hợp lệ bị từ chối")
    void testSymbolAndPriceValidation() {
        ForecastResponse f1 = createValidForecast();
        f1.setSymbol("");
        assertFalse(ForecastQualityPolicy.isValid(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setCurrentPrice(BigDecimal.ZERO);
        assertFalse(ForecastQualityPolicy.isValid(f2));

        ForecastResponse f3 = createValidForecast();
        f3.setCurrentPrice(new BigDecimal("-50.0"));
        assertFalse(ForecastQualityPolicy.isValid(f3));
    }

    @Test
    @DisplayName("Độ tin cậy ngoài phạm vi 0-100 bị từ chối")
    void testInvalidConfidenceRejected() {
        ForecastResponse f1 = createValidForecast();
        f1.setConfidenceScore(-5);
        assertFalse(ForecastQualityPolicy.isValid(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setConfidenceScore(105);
        assertFalse(ForecastQualityPolicy.isValid(f2));

        ForecastResponse f3 = createValidForecast();
        f3.setConfidenceScore(null);
        assertFalse(ForecastQualityPolicy.isValid(f3));
    }

    @Test
    @DisplayName("Support Level <= 0 hoặc Resistance Level <= 0 bị từ chối")
    void testNonPositiveLevelsRejected() {
        ForecastResponse f1 = createValidForecast();
        f1.setSupportLevel(BigDecimal.ZERO);
        assertFalse(ForecastQualityPolicy.isValid(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setResistanceLevel(new BigDecimal("-100"));
        assertFalse(ForecastQualityPolicy.isValid(f2));
    }

    @Test
    @DisplayName("Support Level lớn hơn Resistance Level bị từ chối")
    void testSupportGreaterThanResistanceRejected() {
        ForecastResponse f = createValidForecast();
        f.setSupportLevel(new BigDecimal("70000.00"));
        f.setResistanceLevel(new BigDecimal("65000.00"));
        assertFalse(ForecastQualityPolicy.isValid(f));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f));
    }

    @Test
    @DisplayName("Key Drivers ít hơn 2 hoặc nhiều hơn 5 ý bị từ chối")
    void testKeyDriversCountValidation() {
        ForecastResponse f1 = createValidForecast();
        f1.setKeyDrivers(List.of("Chỉ có một ý"));
        assertFalse(ForecastQualityPolicy.isValid(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setKeyDrivers(List.of("Ý 1 dài chuẩn", "Ý 2 dài chuẩn", "Ý 3 dài chuẩn", "Ý 4 dài chuẩn", "Ý 5 dài chuẩn", "Ý 6 thừa"));
        assertFalse(ForecastQualityPolicy.isValid(f2));

        ForecastResponse f3 = createValidForecast();
        f3.setKeyDrivers(null);
        assertFalse(ForecastQualityPolicy.isValid(f3));
    }

    @Test
    @DisplayName("Key Drivers hoặc nhận định chứa placeholder / AI slop bị từ chối")
    void testForbiddenPhrasesRejected() {
        ForecastResponse f1 = createValidForecast();
        f1.setTechnicalOutlook("Vui lòng đợi tính toán chiến lược (AI)...");
        assertFalse(ForecastQualityPolicy.isValid(f1));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setKeyDrivers(List.of("Không có thông tin chi tiết", "Đang cập nhật từ hệ thống"));
        assertFalse(ForecastQualityPolicy.isValid(f2));
    }

    @Test
    @DisplayName("Nội dung không phải tiếng Việt có dấu bị từ chối (chống slop tiếng Anh / rỗng)")
    void testVietnameseLanguageEnforcement() {
        ForecastResponse f1 = createValidForecast();
        f1.setTechnicalOutlook("The technical pattern looks bullish and RSI is healthy."); // Pure English
        assertFalse(ForecastQualityPolicy.isValid(f1));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f1));

        ForecastResponse f2 = createValidForecast();
        f2.setFundamentalOutlook("Macro conditions remain favorable for crypto assets."); // Pure English
        assertFalse(ForecastQualityPolicy.isValid(f2));

        ForecastResponse f3 = createValidForecast();
        f3.setKeyDrivers(List.of("Institutional inflows increasing", "ETF volumes breaking records")); // Pure English
        assertFalse(ForecastQualityPolicy.isValid(f3));
    }

    @Test
    @DisplayName("Chặn mixed language: câu tiếng Anh chèn 1 từ tiếng Việt có dấu bị từ chối")
    void testRejectMixedLanguageEnglishSentencesWithSingleVietnameseWord() {
        // 1. "Nvidia reports quarterly revenue tăng"
        assertFalse(ForecastQualityPolicy.isSubstantialVietnamese("Nvidia reports quarterly revenue tăng"));
        ForecastResponse f1 = createValidForecast();
        f1.setTechnicalOutlook("Nvidia reports quarterly revenue tăng");
        assertFalse(ForecastQualityPolicy.isValid(f1));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f1));

        // 2. "Market outlook bullish nhưng volatile"
        assertFalse(ForecastQualityPolicy.isSubstantialVietnamese("Market outlook bullish nhưng volatile"));
        ForecastResponse f2 = createValidForecast();
        f2.setFundamentalOutlook("Market outlook bullish nhưng volatile");
        assertFalse(ForecastQualityPolicy.isValid(f2));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f2));
    }

    @Test
    @DisplayName("Chấp nhận tiếng Việt thực chất dù chứa tên riêng, ticker, thuật ngữ tài chính chuẩn")
    void testAcceptSubstantialVietnameseWithLegitimateTickersAndAcronyms() {
        // 3. "Nvidia công bố doanh thu quý tăng trưởng mạnh"
        assertTrue(ForecastQualityPolicy.isSubstantialVietnamese("Nvidia công bố doanh thu quý tăng trưởng mạnh"));
        ForecastResponse f1 = createValidForecast();
        f1.setTechnicalOutlook("Nvidia công bố doanh thu quý tăng trưởng mạnh và vượt kỳ vọng.");
        assertTrue(ForecastQualityPolicy.isValid(f1));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(f1));

        // 4. "Bitcoin ETF ghi nhận dòng vốn tích cực"
        assertTrue(ForecastQualityPolicy.isSubstantialVietnamese("Bitcoin ETF ghi nhận dòng vốn tích cực"));
        ForecastResponse f2 = createValidForecast();
        f2.setFundamentalOutlook("Bitcoin ETF ghi nhận dòng vốn tích cực trong các phiên gần đây.");
        assertTrue(ForecastQualityPolicy.isValid(f2));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(f2));

        // 5. Chấp nhận tiếng Việt chứa BTC, ETH, USD, RSI, MACD
        String textWithAcronyms = "Chỉ báo RSI và MACD của BTC và ETH theo cặp USD đang phát tín hiệu tích cực.";
        assertTrue(ForecastQualityPolicy.isSubstantialVietnamese(textWithAcronyms));
        ForecastResponse f3 = createValidForecast();
        f3.setKeyDrivers(List.of(
                "Chỉ báo RSI và MACD của BTC và ETH theo cặp USD đang phát tín hiệu tích cực.",
                "Dòng tiền tổ chức tiếp tục gia tăng tích lũy vào hệ sinh thái."
        ));
        assertTrue(ForecastQualityPolicy.isValid(f3));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(f3));
    }

    @Test
    @DisplayName("RC5: Siết policy ngôn ngữ - từ chối thuật ngữ tiếng Anh dễ dịch và chấp nhận thuần tiếng Việt")
    void testStrictVietnameseFinancialTerminologyPolicy() {
        // 1. Reject: "Bullish support resistance tăng trưởng"
        String text1 = "Bullish support resistance tăng trưởng";
        assertFalse(ForecastQualityPolicy.isSubstantialVietnamese(text1));
        assertTrue(ForecastQualityPolicy.containsForbiddenUntranslatedTerm(text1));
        ForecastResponse f1 = createValidForecast();
        f1.setTechnicalOutlook("Bullish support resistance tăng trưởng rõ nét.");
        assertFalse(ForecastQualityPolicy.isValid(f1));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f1));

        // 2. Reject: "Giá BTC bullish và volume tăng"
        String text2 = "Giá BTC bullish và volume tăng";
        assertFalse(ForecastQualityPolicy.isSubstantialVietnamese(text2));
        assertTrue(ForecastQualityPolicy.containsForbiddenUntranslatedTerm(text2));
        ForecastResponse f2 = createValidForecast();
        f2.setTechnicalOutlook("Giá BTC bullish và volume tăng mạnh.");
        assertFalse(ForecastQualityPolicy.isValid(f2));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f2));

        // 3. Reject: "Market sentiment tích cực, action plan là long"
        String text3 = "Market sentiment tích cực, action plan là long";
        assertFalse(ForecastQualityPolicy.isSubstantialVietnamese(text3));
        assertTrue(ForecastQualityPolicy.containsForbiddenUntranslatedTerm(text3));
        ForecastResponse f3 = createValidForecast();
        f3.setFundamentalOutlook("Market sentiment tích cực, action plan là long trong ngắn hạn.");
        assertFalse(ForecastQualityPolicy.isValid(f3));
        assertThrows(ForecastUnavailableException.class, () -> ForecastQualityPolicy.validateOrThrow(f3));

        // 4. Accept: "Giá BTC duy trì xu hướng tăng với khối lượng giao dịch cải thiện"
        String text4 = "Giá BTC duy trì xu hướng tăng với khối lượng giao dịch cải thiện";
        assertTrue(ForecastQualityPolicy.isSubstantialVietnamese(text4));
        assertFalse(ForecastQualityPolicy.containsForbiddenUntranslatedTerm(text4));
        ForecastResponse f4 = createValidForecast();
        f4.setTechnicalOutlook("Giá BTC duy trì xu hướng tăng với khối lượng giao dịch cải thiện đáng kể.");
        assertTrue(ForecastQualityPolicy.isValid(f4));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(f4));

        // 5. Accept: "Vùng hỗ trợ và kháng cự được xác định từ dữ liệu nến"
        String text5 = "Vùng hỗ trợ và kháng cự được xác định từ dữ liệu nến";
        assertTrue(ForecastQualityPolicy.isSubstantialVietnamese(text5));
        assertFalse(ForecastQualityPolicy.containsForbiddenUntranslatedTerm(text5));
        ForecastResponse f5 = createValidForecast();
        f5.setTechnicalOutlook("Vùng hỗ trợ và kháng cự được xác định từ dữ liệu nến thực tế.");
        assertTrue(ForecastQualityPolicy.isValid(f5));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(f5));

        // 6. Accept: Nội dung chứa ticker, tên riêng, RSI, MACD, ETF và USD
        String text6 = "Chỉ báo RSI và MACD của Nvidia và Apple theo cặp USD cùng các quỹ Bitcoin ETF đang thu hút dòng tiền.";
        assertTrue(ForecastQualityPolicy.isSubstantialVietnamese(text6));
        assertFalse(ForecastQualityPolicy.containsForbiddenUntranslatedTerm(text6));
        ForecastResponse f6 = createValidForecast();
        f6.setKeyDrivers(List.of(
                "Chỉ báo RSI và MACD của Nvidia và Apple theo cặp USD cùng các quỹ Bitcoin ETF đang thu hút dòng tiền.",
                "Dòng tiền phân bổ vào hệ sinh thái Ethereum và Solana tiếp tục duy trì ổn định."
        ));
        f6.setTechnicalOutlook("Động lượng kỹ thuật trên cặp BTC và ETH theo USD duy trì tín hiệu tích cực.");
        f6.setFundamentalOutlook("Tâm lý thị trường ổn định khi các quỹ ETF ghi nhận giá trị ròng gia tăng.");
        assertTrue(ForecastQualityPolicy.isValid(f6));
        assertDoesNotThrow(() -> ForecastQualityPolicy.validateOrThrow(f6));
    }
}
