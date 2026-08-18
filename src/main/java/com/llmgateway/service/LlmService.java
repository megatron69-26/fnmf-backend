package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Service gọi LLM Chat Completions API.
 *
 * Giai đoạn 2: Thêm logging chi tiết (thời gian, cost) và retry khi lỗi.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${openai.default-model:gpt-4o-mini}")
    private String defaultModel;

    // --- Cấu hình Retry (đọc từ application.properties) ---
    @Value("${gateway.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${gateway.retry.delay-ms:1000}")
    private long retryDelayMs;

    // --- Giá ước tính (USD per 1M tokens) ---
    private static final double INPUT_COST_PER_MILLION = 0.15;
    private static final double OUTPUT_COST_PER_MILLION = 0.60;

    public LlmService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Gửi message tới LLM API, có retry tự động khi gặp lỗi server (5xx) hoặc timeout.
     */
    public ChatResponse chat(String model, String message) {
        String selectedModel = (model != null && !model.isBlank()) ? model : defaultModel;

        // Ghi lại thời điểm bắt đầu để đo tốc độ phản hồi
        long startTime = System.currentTimeMillis();

        // Gọi API với retry logic
        ChatResponse response = callWithRetry(selectedModel, message);

        // Tính thời gian phản hồi
        long durationMs = System.currentTimeMillis() - startTime;

        // Tính cost ước tính (USD)
        double estimatedCost = estimateCost(response.getUsage());

        // Log đầy đủ thông tin cho mỗi request
        log.info("REQUEST COMPLETED | model={} | prompt_tokens={} | completion_tokens={} | total_tokens={} | duration_ms={} | estimated_cost_usd={} ",
                response.getModel(),
                response.getUsage().getPromptTokens(),
                response.getUsage().getCompletionTokens(),
                response.getUsage().getTotalTokens(),
                durationMs,
                String.format("%.6f", estimatedCost)
        );

        return response;
    }

    /**
     * Gọi API có retry tự động.
     *
     * Tại sao cần retry?
     * - API bên ngoài (OpenAI, Gemini) không ổn định 100%.
     * - Đôi khi server quá tải trả về lỗi 500/502/503, nhưng thử lại sau 1-2 giây thì lại OK.
     * - Nếu không retry, user phải tự bấm gửi lại => trải nghiệm kém.
     *
     * Cách hoạt động: Exponential Backoff
     * - Lần 1 thất bại: chờ 1 giây rồi thử lại
     * - Lần 2 thất bại: chờ 2 giây rồi thử lại
     * - Lần 3 thất bại: bỏ cuộc, báo lỗi cho user
     * Thời gian chờ tăng gấp đôi mỗi lần để tránh "đập" liên tục vào server đang quá tải.
     */
    private ChatResponse callWithRetry(String model, String message) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                return callLlmApi(model, message);
            } catch (RetryableException e) {
                // Lỗi có thể retry (5xx, timeout)
                lastException = e;
                if (attempt < maxRetryAttempts) {
                    long waitTime = retryDelayMs * attempt;  // Exponential backoff
                    log.warn("RETRY {}/{} | model={} | reason={} | waiting_ms={}",
                            attempt, maxRetryAttempts, model, e.getMessage(), waitTime);
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }
            } catch (NonRetryableException e) {
                // Lỗi không thể retry (400, 401, 403) — báo lỗi ngay
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        // Hết số lần retry, bỏ cuộc
        log.error("ALL RETRIES EXHAUSTED | model={} | attempts={}", model, maxRetryAttempts);
        throw new RuntimeException("LLM API failed after " + maxRetryAttempts + " attempts: " + lastException.getMessage());
    }

    /**
     * Gọi LLM API một lần duy nhất. Ném ra RetryableException hoặc NonRetryableException.
     */
    private ChatResponse callLlmApi(String model, String message) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "user", "content", message)
                    )
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            // Phân loại lỗi theo mã HTTP
            if (status >= 500) {
                // 5xx = Server error (server OpenAI/Gemini bị lỗi) => CÓ THỂ retry
                throw new RetryableException("Server error " + status);
            } else if (status == 429) {
                // 429 = Rate limited bởi OpenAI => CÓ THỂ retry (chờ rồi thử lại)
                throw new RetryableException("Rate limited by API (429)");
            } else if (status != 200) {
                // 400, 401, 403 = Lỗi do mình (sai key, sai request) => KHÔNG retry
                throw new NonRetryableException("API error " + status + ": " + response.body());
            }

            // Parse response JSON
            JsonNode root = objectMapper.readTree(response.body());

            JsonNode choices = root.path("choices");
            if (choices.isEmpty() || !choices.isArray() || choices.size() == 0) {
                throw new NonRetryableException("API returned empty choices");
            }

            String reply = choices.get(0).path("message").path("content").asText();

            JsonNode usageNode = root.path("usage");
            ChatResponse.Usage usage = new ChatResponse.Usage(
                    usageNode.path("prompt_tokens").asInt(),
                    usageNode.path("completion_tokens").asInt(),
                    usageNode.path("total_tokens").asInt()
            );

            return new ChatResponse(reply, model, usage);

        } catch (RetryableException | NonRetryableException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            // Timeout = CÓ THỂ retry
            throw new RetryableException("Request timeout: " + e.getMessage());
        } catch (Exception e) {
            throw new NonRetryableException("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Tính chi phí ước tính (USD) dựa trên số token đã dùng.
     */
    private double estimateCost(ChatResponse.Usage usage) {
        double inputCost = (usage.getPromptTokens() / 1_000_000.0) * INPUT_COST_PER_MILLION;
        double outputCost = (usage.getCompletionTokens() / 1_000_000.0) * OUTPUT_COST_PER_MILLION;
        return inputCost + outputCost;
    }

    // --- Hai loại Exception nội bộ để phân biệt lỗi có thể retry và không thể retry ---

    private static class RetryableException extends RuntimeException {
        RetryableException(String message) {
            super(message);
        }
    }

    private static class NonRetryableException extends RuntimeException {
        NonRetryableException(String message) {
            super(message);
        }
    }
}
