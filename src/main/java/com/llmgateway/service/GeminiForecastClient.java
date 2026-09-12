package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.entity.NewsAiCache;
import com.llmgateway.exception.ForecastUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client giao tiếp trực tiếp với Gemini AI để sinh nhận định và dự báo thị trường.
 * Tuân thủ nghiêm ngặt:
 * - Bảo vệ khóa bí mật (không bao giờ log apiKey hoặc body nhạy cảm).
 * - Sử dụng tối đa 30 cây nến thực tế (OHLCV).
 * - Thẩm định kết quả qua ForecastQualityPolicy trước khi trả về.
 * - Khi gặp lỗi, luôn ném ForecastUnavailableException, không tự bịa đặt dữ liệu (Zero Slop / Zero Fake).
 */
@Component
public class GeminiForecastClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiForecastClient.class);
    private static final int MAX_CANDLES_INPUT = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.key:}")
    private String geminiApiKey;

    @Value("${openai.api.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}")
    private String geminiApiUrl;

    @Value("${openai.default-model:gemini-3.6-flash}")
    private String geminiModel;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiForecastClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public GeminiForecastClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public ForecastResponse requestForecast(String symbol,
                                           MarketPriceDto priceDto,
                                           List<CandleDto> candles,
                                           List<NewsAiCache> recentNews,
                                           String timeframe) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.warn("Gemini API key is not configured for market forecasting");
            throw new ForecastUnavailableException("Chưa cấu hình dịch vụ AI phân tích thị trường");
        }

        if (candles == null || candles.isEmpty()) {
            throw new ForecastUnavailableException("Không đủ dữ liệu nến thị trường thực tế để tạo nhận định");
        }

        int availableCandles = Math.min(candles.size(), MAX_CANDLES_INPUT);
        List<CandleDto> inputCandles = candles.subList(candles.size() - availableCandles, candles.size());

        String systemPrompt = """
            Bạn là Chuyên gia Chiến lược Đầu tư và Phân tích Định lượng Cấp cao của quỹ đầu tư FNMF.
            Nhiệm vụ: Phân tích DỮ LIỆU KỸ THUẬT (chuỗi nến thực tế OHLCV) và DỮ LIỆU TÂM LÝ TIN TỨC để dự báo thị trường.
            
            QUY TẮC BẮT BUỘC:
            1. Ước lượng vùng giá Support (ngưỡng hỗ trợ) và Resistance (ngưỡng kháng cự) hợp lý dựa trên biến động nến thực tế (> 0 và Support <= Resistance).
            2. Xác định Xu hướng: BULLISH_UPTREND, BEARISH_DOWNTREND, hoặc SIDEWAYS_CONSOLIDATION.
            3. Khuyến nghị dứt khoát: STRONG_BUY, BUY, HOLD, SELL, hoặc STRONG_SELL.
            4. Chỉ số độ tin cậy confidenceScore bắt buộc là một số nguyên từ 0 đến 100.
            5. Toàn bộ nội dung gửi ra UI (keyDrivers, technicalOutlook, fundamentalOutlook) PHẢI VIẾT THUẦN TIẾNG VIỆT CHUYÊN NGHIỆP:
               - TUYỆT ĐỐI KHÔNG DÙNG CÁC TỪ TIẾNG ANH CHƯA DỊCH: 'bullish', 'bearish', 'sideways', 'support', 'resistance', 'long', 'short', 'volume', 'outlook', 'sentiment', 'forecast', 'action plan'.
               - BẮT BUỘC DÙNG THUẬT NGỮ TIẾNG VIỆT TƯƠNG ỨNG: 'xu hướng tăng', 'xu hướng giảm', 'đi ngang', 'hỗ trợ', 'kháng cự', 'vị thế mua', 'vị thế bán', 'khối lượng', 'nhận định', 'tâm lý thị trường', 'kịch bản tham khảo'.
               - Chỉ giữ nguyên các mã ticker, tên riêng và từ viết tắt nghiệp vụ thực sự cần thiết (BTC, ETH, USD, USDT, VND, ETF, RSI, MACD, FED, SEC, FOMC, GDP, CPI, DXY, EMA, SMA, OHLCV, Nvidia, Apple, Microsoft, Tesla, Binance, Coinbase, Bitcoin, Ethereum, Solana).
            6. QUY ĐỊNH ĐỘ DÀI VÀ NGẮN GỌN (BẮT BUỘC):
               - technicalOutlook: đúng 1 câu tiếng Việt, tối đa 140 ký tự.
               - fundamentalOutlook: đúng 1 câu tiếng Việt, tối đa 140 ký tự.
               - keyDrivers: đúng 3 ý; mỗi ý là 1 câu tiếng Việt, tối đa 85 ký tự.
               - Tuyệt đối không xuống dòng (không dùng ký tự \\n hoặc ngắt dòng) bên trong từng chuỗi văn bản.
               - Không lặp lại giá hiện tại, ngưỡng hỗ trợ và kháng cự ở nhiều phần.
               - Chỉ giữ thông tin quan trọng nhất, súc tích, không diễn giải dài dòng.
               - Tuyệt đối không dùng Markdown (in đậm, in nghiêng, gạch đầu dòng -, *, •) bên trong nội dung chuỗi JSON.
            
            VÍ DỤ PHONG CÁCH MONG MUỐN:
            technicalOutlook: "Giá đang giữ trên vùng hỗ trợ, trong khi khối lượng cho thấy lực bán suy yếu."
            fundamentalOutlook: "Tâm lý thị trường ổn định và chưa xuất hiện thông tin vĩ mô bất lợi đáng kể."
            keyDrivers: [
              "Giá vẫn duy trì trên vùng hỗ trợ ngắn hạn.",
              "Khối lượng giảm trong các phiên điều chỉnh.",
              "Cấu trúc đỉnh và đáy chưa phá vỡ xu hướng tăng."
            ]
            
            ĐỊNH DẠNG:
            Trả về DUY NHẤT một chuỗi JSON hợp lệ, KHÔNG bọc mã markdown ```json ... ```.
            Cấu trúc JSON bắt buộc:
            {
              "trendPrediction": "<BULLISH_UPTREND | BEARISH_DOWNTREND | SIDEWAYS_CONSOLIDATION>",
              "supportLevel": <số thập phân ước lượng ngưỡng hỗ trợ>,
              "resistanceLevel": <số thập phân ước lượng ngưỡng kháng cự>,
              "recommendation": "<STRONG_BUY | BUY | HOLD | SELL | STRONG_SELL>",
              "confidenceScore": <số nguyên từ 0 đến 100>,
              "keyDrivers": ["<luận điểm 1 tối đa 85 ký tự>", "<luận điểm 2 tối đa 85 ký tự>", "<luận điểm 3 tối đa 85 ký tự>"],
              "technicalOutlook": "<đúng 1 câu tiếng Việt tối đa 140 ký tự>",
              "fundamentalOutlook": "<đúng 1 câu tiếng Việt tối đa 140 ký tự>"
            }
            """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Mã tài sản: ").append(symbol).append(" (").append(priceDto.getName() != null ? priceDto.getName() : symbol).append(")\n");
        userPrompt.append("Giá hiện tại: $").append(priceDto.getPrice()).append("\n");
        if (priceDto.getChange24h() != null) {
            userPrompt.append("Biến động 24h: ").append(priceDto.getChange24h()).append("%\n\n");
        }

        userPrompt.append("--- DỮ LIỆU ").append(inputCandles.size()).append(" CÂY NẾN THỰC TẾ GẦN NHẤT ---\n");
        for (CandleDto c : inputCandles) {
            userPrompt.append(String.format("Ngày %s: Open=%s, High=%s, Low=%s, Close=%s, Vol=%s\n",
                    c.getTime(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume()));
        }

        if (recentNews != null && !recentNews.isEmpty()) {
            userPrompt.append("\n--- TÂM LÝ TIN TỨC VĨ MÔ GẦN NHẤT ---\n");
            int newsCount = Math.min(3, recentNews.size());
            for (int i = 0; i < newsCount; i++) {
                NewsAiCache n = recentNews.get(i);
                userPrompt.append(String.format("• [%s] %s (Tâm lý: %s, Lý do: %s)\n",
                        n.getSymbol(), n.getTitle(), n.getSentiment(), n.getReason()));
            }
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", geminiModel,
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt.toString())
                    )
            );

            String payload = objectMapper.writeValueAsString(requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(geminiApiUrl))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + geminiApiKey)
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Gemini forecast endpoint returned non-200 status: {}, body: {}", response.statusCode(), response.body());
                throw new ForecastUnavailableException("Dịch vụ AI phản hồi mã trạng thái " + response.statusCode());
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new ForecastUnavailableException("Dịch vụ AI trả về phản hồi rỗng");
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ForecastUnavailableException("Dịch vụ AI không trả về nội dung dự báo");
            }

            String rawText = choices.get(0).path("message").path("content").asText("").trim();
            if (rawText.startsWith("```json")) {
                rawText = rawText.substring(7);
            }
            if (rawText.startsWith("```")) {
                rawText = rawText.substring(3);
            }
            if (rawText.endsWith("```")) {
                rawText = rawText.substring(0, rawText.length() - 3);
            }
            rawText = rawText.trim();

            JsonNode parsed = objectMapper.readTree(rawText);

            if (!parsed.hasNonNull("trendPrediction") || !parsed.get("trendPrediction").isTextual()) {
                throw new ForecastUnavailableException("Trường trendPrediction không hợp lệ hoặc không phải chuỗi");
            }
            String trend = parsed.get("trendPrediction").asText().trim();

            if (!parsed.hasNonNull("supportLevel") || !parsed.get("supportLevel").isNumber()) {
                throw new ForecastUnavailableException("Trường supportLevel phải là kiểu số JSON");
            }
            BigDecimal support = new BigDecimal(parsed.get("supportLevel").asText()).setScale(2, RoundingMode.HALF_UP);

            if (!parsed.hasNonNull("resistanceLevel") || !parsed.get("resistanceLevel").isNumber()) {
                throw new ForecastUnavailableException("Trường resistanceLevel phải là kiểu số JSON");
            }
            BigDecimal resistance = new BigDecimal(parsed.get("resistanceLevel").asText()).setScale(2, RoundingMode.HALF_UP);

            if (!parsed.hasNonNull("recommendation") || !parsed.get("recommendation").isTextual()) {
                throw new ForecastUnavailableException("Trường recommendation không hợp lệ hoặc không phải chuỗi");
            }
            String recommendation = parsed.get("recommendation").asText().trim().toUpperCase();

            JsonNode confNode = parsed.get("confidenceScore");
            if (confNode == null || !confNode.isIntegralNumber()) {
                throw new ForecastUnavailableException("Trường confidenceScore phải là số nguyên JSON thực tế (không ép kiểu từ chuỗi hoặc số thực)");
            }
            int confidence = confNode.asInt();

            if (!parsed.has("keyDrivers") || !parsed.get("keyDrivers").isArray()) {
                throw new ForecastUnavailableException("Trường keyDrivers phải là mảng JSON");
            }
            List<String> drivers = new ArrayList<>();
            for (JsonNode d : parsed.get("keyDrivers")) {
                if (d.isTextual() && !d.asText().isBlank()) {
                    drivers.add(d.asText().trim());
                } else {
                    throw new ForecastUnavailableException("Mỗi phần tử trong keyDrivers phải là chuỗi có nội dung");
                }
            }

            if (!parsed.hasNonNull("technicalOutlook") || !parsed.get("technicalOutlook").isTextual()) {
                throw new ForecastUnavailableException("Trường technicalOutlook phải là chuỗi");
            }
            String techOutlook = parsed.get("technicalOutlook").asText().trim();

            if (!parsed.hasNonNull("fundamentalOutlook") || !parsed.get("fundamentalOutlook").isTextual()) {
                throw new ForecastUnavailableException("Trường fundamentalOutlook phải là chuỗi");
            }
            String fundOutlook = parsed.get("fundamentalOutlook").asText().trim();

            ForecastResponse forecastResponse = new ForecastResponse(
                    symbol,
                    priceDto.getName() != null ? priceDto.getName() : symbol,
                    priceDto.getPrice(),
                    trend,
                    timeframe != null ? timeframe : "24H_7D",
                    support,
                    resistance,
                    recommendation,
                    confidence,
                    drivers,
                    techOutlook,
                    fundOutlook,
                    "GEMINI",
                    inputCandles.size(),
                    false,
                    LocalDateTime.now()
            );

            // Kiểm duyệt chất lượng khắt khe trước khi chấp nhận kết quả
            ForecastQualityPolicy.validateOrThrow(forecastResponse);

            return forecastResponse;

        } catch (ForecastUnavailableException fe) {
            throw fe;
        } catch (Exception e) {
            log.warn("Lỗi khi xử lý dự báo thị trường qua AI: {}", e.getClass().getSimpleName());
            throw new ForecastUnavailableException("Chưa thể tạo nhận định lúc này. Vui lòng thử lại sau.", e);
        }
    }
}
