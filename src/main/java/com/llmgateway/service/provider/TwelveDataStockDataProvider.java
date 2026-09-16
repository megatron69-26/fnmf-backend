package com.llmgateway.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.CandleDto;
import com.llmgateway.dto.market.NewsArticleDto;
import com.llmgateway.exception.MarketDataUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TwelveDataStockDataProvider implements MarketContentProvider {

    private static final Logger log = LoggerFactory.getLogger(TwelveDataStockDataProvider.class);

    public static final String PROVIDER_NAME = "TWELVE_DATA";

    private static final Set<String> SUPPORTED_SYMBOLS = Set.of(
            MarketSymbolConfig.CANONICAL_TSLA,
            MarketSymbolConfig.CANONICAL_AMZN,
            MarketSymbolConfig.CANONICAL_META,
            MarketSymbolConfig.CANONICAL_JPM
    );

    private static final String DEFAULT_BASE_URL = "https://api.twelvedata.com";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000L; // 1 phút cooldown cho 429

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${twelvedata.api.key:${TWELVE_DATA_API_KEY:}}")
    private String apiKey;

    @Value("${twelvedata.base-url:https://api.twelvedata.com}")
    private String baseUrl = DEFAULT_BASE_URL;

    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    @Autowired
    public TwelveDataStockDataProvider(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    public TwelveDataStockDataProvider(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean supports(String symbol) {
        if (symbol == null) return false;
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        return SUPPORTED_SYMBOLS.contains(canonical);
    }

    @Override
    public BigDecimal getLatestPrice(String symbol) {
        List<CandleDto> candles = getCandles(symbol, "1m", 1);
        if (candles.isEmpty()) {
            throw new MarketDataUnavailableException("Không lấy được giá mới nhất từ Twelve Data cho mã: " + symbol);
        }
        return candles.get(candles.size() - 1).getClose();
    }

    @Override
    public List<CandleDto> getCandles(String symbol, String interval, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        validateConfigured();
        checkCooldown(canonical);

        int maxLimit = Math.min(Math.max(limit, 1), 30);
        String tdInterval = ("1m".equalsIgnoreCase(interval)) ? "1min" : "1day";

        String url = String.format("%s/time_series?symbol=%s&interval=%s&outputsize=%d&apikey=%s",
                cleanBaseUrl(), canonical, tdInterval, maxLimit, apiKey.trim());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 429) {
                cooldownMap.put(canonical, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                log.warn("Twelve Data rate limited (HTTP 429) for symbol {}, cooldown activated", canonical);
                throw new MarketDataUnavailableException("Nhà cung cấp Twelve Data đang giới hạn tần suất cho mã: " + canonical);
            }

            if (statusCode == 401 || statusCode == 403) {
                log.error("Twelve Data authentication failed (HTTP {})", statusCode);
                throw new MarketDataUnavailableException("Cấu hình API key Twelve Data không hợp lệ");
            }

            if (statusCode != 200) {
                log.warn("Twelve Data time_series endpoint returned HTTP {} for symbol {}", statusCode, canonical);
                throw new MarketDataUnavailableException("Nhà cung cấp Twelve Data trả về mã lỗi: " + statusCode);
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new MarketDataUnavailableException("Twelve Data trả về phản hồi rỗng cho mã: " + canonical);
            }

            JsonNode root = objectMapper.readTree(body);
            if (root.has("status") && "error".equalsIgnoreCase(root.path("status").asText())) {
                int code = root.path("code").asInt(0);
                if (code == 429) {
                    cooldownMap.put(canonical, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                    throw new MarketDataUnavailableException("Twelve Data vượt giới hạn tần suất cho mã: " + canonical);
                }
                log.warn("Twelve Data error status code {}", code);
                throw new MarketDataUnavailableException("Twelve Data báo lỗi cho mã: " + canonical);
            }

            JsonNode valuesNode = root.path("values");
            if (!valuesNode.isArray() || valuesNode.isEmpty()) {
                log.warn("Twelve Data returned no values for symbol {}", canonical);
                return Collections.emptyList();
            }

            List<CandleDto> result = new ArrayList<>();
            for (JsonNode item : valuesNode) {
                CandleDto candle = parseValueNode(item);
                if (candle != null) {
                    result.add(candle);
                }
            }

            // Twelve Data trả về từ mới nhất đến cũ nhất -> đảo ngược để theo thứ tự thời gian tăng dần
            Collections.reverse(result);
            return result;
        } catch (MarketDataUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Lỗi khi kết nối Twelve Data time_series cho mã {}: {}", canonical, e.getClass().getSimpleName());
            throw new MarketDataUnavailableException("Không thể kết nối đến Twelve Data cho mã: " + canonical);
        }
    }

    @Override
    public List<NewsArticleDto> getLatestNews(String symbol, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        validateConfigured();
        checkCooldown(canonical);

        String url = String.format("%s/press_releases?symbol=%s&apikey=%s", cleanBaseUrl(), canonical, apiKey.trim());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 429) {
                cooldownMap.put(canonical, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                return Collections.emptyList();
            }

            if (statusCode != 200) {
                log.debug("Twelve Data press_releases returned HTTP {} for symbol {}", statusCode, canonical);
                return Collections.emptyList();
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(body);
            if (root.has("status") && "error".equalsIgnoreCase(root.path("status").asText())) {
                return Collections.emptyList();
            }

            JsonNode pressReleases = root.path("press_releases");
            if (!pressReleases.isArray() || pressReleases.isEmpty()) {
                return Collections.emptyList();
            }

            int maxLimit = Math.min(Math.max(limit, 1), 10);
            List<NewsArticleDto> articles = new ArrayList<>();
            for (int i = 0; i < Math.min(pressReleases.size(), maxLimit); i++) {
                JsonNode item = pressReleases.get(i);
                String title = item.path("title").asText("").trim();
                String summary = item.path("body").asText("").trim();
                String datetime = item.path("datetime").asText("").trim();
                if (!title.isBlank()) {
                    articles.add(new NewsArticleDto(title, summary, "", datetime, PROVIDER_NAME));
                }
            }
            return articles;
        } catch (Exception e) {
            log.debug("Lỗi khi tải tin tức từ Twelve Data cho mã {}: {}", canonical, e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private void validateConfigured() {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new MarketDataUnavailableException("Chưa cấu hình API key cho Twelve Data");
        }
    }

    private void checkCooldown(String symbol) {
        Long until = cooldownMap.get(symbol);
        if (until != null && System.currentTimeMillis() < until) {
            throw new MarketDataUnavailableException("Mã " + symbol + " đang trong thời gian chờ (cooldown) của Twelve Data");
        }
    }

    private String cleanBaseUrl() {
        String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : DEFAULT_BASE_URL;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private CandleDto parseValueNode(JsonNode node) {
        if (!node.hasNonNull("open") || !node.hasNonNull("high") || !node.hasNonNull("low") ||
                !node.hasNonNull("close") || !node.hasNonNull("datetime")) {
            return null;
        }

        try {
            BigDecimal open = new BigDecimal(node.path("open").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = new BigDecimal(node.path("high").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = new BigDecimal(node.path("low").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal close = new BigDecimal(node.path("close").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal volume = node.hasNonNull("volume")
                    ? new BigDecimal(node.path("volume").asText().trim()).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            if (open.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(BigDecimal.ZERO) <= 0 ||
                    low.compareTo(BigDecimal.ZERO) <= 0 || close.compareTo(BigDecimal.ZERO) <= 0 ||
                    volume.compareTo(BigDecimal.ZERO) < 0) {
                return null;
            }

            if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0 ||
                    low.compareTo(open) > 0 || low.compareTo(close) > 0) {
                return null;
            }

            String timeStr = node.path("datetime").asText().trim();
            return new CandleDto(timeStr, open, high, low, close, volume);
        } catch (Exception e) {
            return null;
        }
    }
}
