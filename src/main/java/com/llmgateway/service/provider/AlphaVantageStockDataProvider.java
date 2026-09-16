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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AlphaVantageStockDataProvider implements MarketContentProvider {

    private static final Logger log = LoggerFactory.getLogger(AlphaVantageStockDataProvider.class);

    public static final String PROVIDER_NAME = "ALPHA_VANTAGE";

    private static final Set<String> SUPPORTED_SYMBOLS = Set.of();

    public static final long GENERAL_ERROR_COOLDOWN_MS = 15 * 60 * 1000L; // 15 phút
    public static final long RATE_LIMIT_COOLDOWN_MS = 24 * 60 * 60 * 1000L; // 24 giờ
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${alphavantage.api.key:${ALPHAVANTAGE_API_KEY:}}")
    private String apiKey;

    @Value("${alphavantage.api.url:https://www.alphavantage.co/query}")
    private String apiUrl = "https://www.alphavantage.co/query";

    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    @Autowired
    public AlphaVantageStockDataProvider(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    public AlphaVantageStockDataProvider(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Override
    public boolean supports(String symbol) {
        if (symbol == null) return false;
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        return SUPPORTED_SYMBOLS.contains(canonical);
    }

    @Override
    public BigDecimal getLatestPrice(String symbol) {
        List<CandleDto> candles = getCandles(symbol, "daily", 1);
        if (candles.isEmpty()) {
            throw new MarketDataUnavailableException("Không lấy được giá mới nhất từ Alpha Vantage cho mã: " + symbol);
        }
        return candles.get(candles.size() - 1).getClose();
    }

    @Override
    public List<CandleDto> getCandles(String symbol, String interval, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        validateConfigured();
        checkCooldown(canonical);

        int maxLimit = Math.min(Math.max(limit, 1), 30);
        boolean isIntraday = "1m".equalsIgnoreCase(interval);
        String function = isIntraday ? "TIME_SERIES_INTRADAY&interval=1min" : "TIME_SERIES_DAILY";

        String url = String.format("%s?function=%s&symbol=%s&outputsize=compact&apikey=%s",
                cleanBaseUrl(), function, canonical, apiKey.trim());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 429) {
                log.warn("Alpha Vantage trả HTTP 429 (Rate Limit) cho mã {}, kích hoạt cooldown 24h", canonical);
                cooldownMap.put(canonical, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                throw new MarketDataUnavailableException("Alpha Vantage đang vượt hạn mức (429) cho mã: " + canonical);
            }

            if (statusCode == 401 || statusCode == 403) {
                log.error("Alpha Vantage xác thực thất bại (HTTP {})", statusCode);
                throw new MarketDataUnavailableException("Cấu hình API key Alpha Vantage không hợp lệ");
            }

            if (statusCode != 200) {
                log.warn("Alpha Vantage trả HTTP status {} cho mã {}", statusCode, canonical);
                cooldownMap.put(canonical, System.currentTimeMillis() + GENERAL_ERROR_COOLDOWN_MS);
                throw new MarketDataUnavailableException("Alpha Vantage trả về lỗi HTTP: " + statusCode);
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                cooldownMap.put(canonical, System.currentTimeMillis() + GENERAL_ERROR_COOLDOWN_MS);
                throw new MarketDataUnavailableException("Alpha Vantage trả về dữ liệu rỗng cho mã: " + canonical);
            }

            JsonNode root = objectMapper.readTree(body);
            if (root.has("Note") || root.has("Information")) {
                log.warn("Alpha Vantage trả thông báo giới hạn tần suất (Note/Information) cho mã {}, kích hoạt cooldown 24h", canonical);
                cooldownMap.put(canonical, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                throw new MarketDataUnavailableException("Alpha Vantage giới hạn tần suất truy vấn cho mã: " + canonical);
            }

            if (root.has("Error Message")) {
                log.warn("Alpha Vantage trả thông báo lỗi (Error Message) cho mã {}, kích hoạt cooldown 15m", canonical);
                cooldownMap.put(canonical, System.currentTimeMillis() + GENERAL_ERROR_COOLDOWN_MS);
                throw new MarketDataUnavailableException("Alpha Vantage báo lỗi cho mã: " + canonical);
            }

            JsonNode timeSeries = isIntraday ? root.path("Time Series (1min)") : root.path("Time Series (Daily)");
            if ((!timeSeries.isObject() || timeSeries.isEmpty()) && isIntraday) {
                // Thử fallback sang daily nếu 1min không có sẵn
                timeSeries = root.path("Time Series (Daily)");
            }

            if (!timeSeries.isObject() || timeSeries.isEmpty()) {
                cooldownMap.put(canonical, System.currentTimeMillis() + GENERAL_ERROR_COOLDOWN_MS);
                log.warn("Alpha Vantage không trả về Time Series hợp lệ cho mã {}", canonical);
                return Collections.emptyList();
            }

            List<String> dates = new ArrayList<>();
            Iterator<String> fieldNames = timeSeries.fieldNames();
            while (fieldNames.hasNext()) {
                dates.add(fieldNames.next());
            }
            Collections.sort(dates);

            List<CandleDto> allCandles = new ArrayList<>(dates.size());
            for (String dateStr : dates) {
                JsonNode dayNode = timeSeries.path(dateStr);
                CandleDto candle = parseCandleNode(dateStr, dayNode);
                if (candle != null) {
                    allCandles.add(candle);
                }
            }

            int startIndex = Math.max(0, allCandles.size() - maxLimit);
            return new ArrayList<>(allCandles.subList(startIndex, allCandles.size()));
        } catch (MarketDataUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Lỗi khi tải dữ liệu từ Alpha Vantage cho mã {}: {}", canonical, e.getClass().getSimpleName());
            cooldownMap.put(canonical, System.currentTimeMillis() + GENERAL_ERROR_COOLDOWN_MS);
            throw new MarketDataUnavailableException("Không thể kết nối đến Alpha Vantage cho mã: " + canonical);
        }
    }

    @Override
    public List<NewsArticleDto> getLatestNews(String symbol, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        validateConfigured();
        checkCooldown(canonical);

        int maxLimit = Math.min(Math.max(limit, 1), 10);
        String url = String.format("%s?function=NEWS_SENTIMENT&tickers=%s&limit=%d&apikey=%s",
                cleanBaseUrl(), canonical, maxLimit, apiKey.trim());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("Note") || root.has("Information") || root.has("Error Message")) {
                return Collections.emptyList();
            }

            JsonNode feedNode = root.path("feed");
            if (!feedNode.isArray() || feedNode.isEmpty()) {
                return Collections.emptyList();
            }

            List<NewsArticleDto> articles = new ArrayList<>();
            for (JsonNode item : feedNode) {
                String title = item.path("title").asText("").trim();
                String summary = item.path("summary").asText("").trim();
                String articleUrl = item.path("url").asText("").trim();
                String timePublished = item.path("time_published").asText("").trim();
                if (!title.isBlank()) {
                    articles.add(new NewsArticleDto(title, summary, articleUrl, timePublished, PROVIDER_NAME));
                }
            }
            return articles;
        } catch (Exception e) {
            log.debug("Lỗi khi tải tin từ Alpha Vantage cho mã {}: {}", canonical, e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private void validateConfigured() {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new MarketDataUnavailableException("Chưa cấu hình API key cho Alpha Vantage");
        }
    }

    private void checkCooldown(String symbol) {
        Long until = cooldownMap.get(symbol);
        if (until != null && System.currentTimeMillis() < until) {
            throw new MarketDataUnavailableException("Mã " + symbol + " đang trong thời gian chờ (cooldown) của Alpha Vantage");
        }
    }

    private String cleanBaseUrl() {
        String url = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl.trim() : "https://www.alphavantage.co/query";
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private CandleDto parseCandleNode(String dateStr, JsonNode node) {
        if (!node.hasNonNull("1. open") || !node.hasNonNull("2. high") ||
                !node.hasNonNull("3. low") || !node.hasNonNull("4. close") ||
                !node.hasNonNull("5. volume")) {
            return null;
        }

        try {
            BigDecimal open = new BigDecimal(node.path("1. open").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = new BigDecimal(node.path("2. high").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = new BigDecimal(node.path("3. low").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal close = new BigDecimal(node.path("4. close").asText().trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal volume = new BigDecimal(node.path("5. volume").asText().trim()).setScale(2, RoundingMode.HALF_UP);

            if (open.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(BigDecimal.ZERO) <= 0 ||
                    low.compareTo(BigDecimal.ZERO) <= 0 || close.compareTo(BigDecimal.ZERO) <= 0 ||
                    volume.compareTo(BigDecimal.ZERO) < 0) {
                return null;
            }

            if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0 ||
                    low.compareTo(open) > 0 || low.compareTo(close) > 0) {
                return null;
            }

            return new CandleDto(dateStr, open, high, low, close, volume);
        } catch (Exception e) {
            return null;
        }
    }
}
