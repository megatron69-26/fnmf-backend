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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AlpacaStockDataProvider implements MarketContentProvider {

    private static final Logger log = LoggerFactory.getLogger(AlpacaStockDataProvider.class);

    public static final String PROVIDER_NAME = "ALPACA";

    private static final Set<String> SUPPORTED_SYMBOLS = Set.of(
            MarketSymbolConfig.CANONICAL_AAPL,
            MarketSymbolConfig.CANONICAL_MSFT,
            MarketSymbolConfig.CANONICAL_NVDA,
            MarketSymbolConfig.CANONICAL_GOOGL
    );

    private static final String DEFAULT_DATA_URL = "https://data.alpaca.markets";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000L; // 1 phút cooldown cho 429

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${alpaca.api.key-id:${ALPACA_API_KEY_ID:}}")
    private String apiKeyId;

    @Value("${alpaca.api.secret-key:${ALPACA_API_SECRET_KEY:}}")
    private String apiSecretKey;

    @Value("${alpaca.data.base-url:https://data.alpaca.markets}")
    private String dataBaseUrl = DEFAULT_DATA_URL;

    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    @Autowired
    public AlpacaStockDataProvider(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
    }

    public AlpacaStockDataProvider(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void setCredentials(String keyId, String secretKey) {
        this.apiKeyId = keyId;
        this.apiSecretKey = secretKey;
    }

    public void setDataBaseUrl(String url) {
        this.dataBaseUrl = url;
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
            candles = getCandles(symbol, "daily", 1);
        }
        if (candles.isEmpty()) {
            throw new MarketDataUnavailableException("Không lấy được giá mới nhất từ Alpaca cho mã: " + symbol);
        }
        return candles.get(candles.size() - 1).getClose();
    }

    @Override
    public List<CandleDto> getCandles(String symbol, String interval, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        validateConfigured();
        checkCooldown(canonical);

        int maxLimit = Math.min(Math.max(limit, 1), 30);
        boolean is1m = "1m".equalsIgnoreCase(interval);
        String timeframe = is1m ? "1Min" : "1Day";
        // Lùi ngày bắt đầu để bảo đảm đủ nến lịch sử cả sau ngày nghỉ cuối tuần
        String startDate = is1m
                ? OffsetDateTime.now(ZoneOffset.UTC).minusDays(5).format(DateTimeFormatter.ISO_LOCAL_DATE)
                : OffsetDateTime.now(ZoneOffset.UTC).minusDays(90).format(DateTimeFormatter.ISO_LOCAL_DATE);

        String url = String.format("%s/v2/stocks/bars?symbols=%s&timeframe=%s&start=%s&limit=%d&sort=desc&feed=iex",
                cleanBaseUrl(), canonical, timeframe, startDate, maxLimit);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID", apiKeyId.trim())
                    .header("APCA-API-SECRET-KEY", apiSecretKey.trim())
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 429) {
                cooldownMap.put(canonical, System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS);
                log.warn("Alpaca rate limited (HTTP 429) for symbol {}, cooldown activated", canonical);
                throw new MarketDataUnavailableException("Nhà cung cấp Alpaca đang giới hạn tần suất cho mã: " + canonical);
            }

            if (statusCode == 401 || statusCode == 403) {
                log.error("Alpaca authentication failed (HTTP {})", statusCode);
                throw new MarketDataUnavailableException("Cấu hình xác thực Alpaca không hợp lệ");
            }

            if (statusCode != 200) {
                log.warn("Alpaca bars endpoint returned HTTP {} for symbol {}", statusCode, canonical);
                throw new MarketDataUnavailableException("Nhà cung cấp Alpaca trả về mã lỗi: " + statusCode);
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new MarketDataUnavailableException("Alpaca trả về dữ liệu rỗng cho mã: " + canonical);
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode barsNode = root.path("bars").path(canonical);
            if (!barsNode.isArray() || barsNode.isEmpty()) {
                log.warn("Alpaca returned no bars for symbol {}", canonical);
                return Collections.emptyList();
            }

            List<CandleDto> result = new ArrayList<>();
            for (JsonNode bar : barsNode) {
                CandleDto candle = parseBarNode(bar, timeframe);
                if (candle != null) {
                    result.add(candle);
                }
            }
            // Alpaca trả về từ mới nhất đến cũ nhất khi sort=desc -> đảo ngược để theo thứ tự thời gian tăng dần
            Collections.reverse(result);
            return result;
        } catch (MarketDataUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Lỗi khi kết nối Alpaca bars cho mã {}: {}", canonical, e.getClass().getSimpleName());
            throw new MarketDataUnavailableException("Không thể kết nối đến Alpaca cho mã: " + canonical);
        }
    }

    @Override
    public List<NewsArticleDto> getLatestNews(String symbol, int limit) {
        String canonical = MarketSymbolConfig.getCanonicalSymbol(symbol);
        validateConfigured();
        checkCooldown(canonical);

        int maxLimit = Math.min(Math.max(limit, 1), 10);
        String url = String.format("%s/v1beta1/news?symbols=%s&limit=%d", cleanBaseUrl(), canonical, maxLimit);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID", apiKeyId.trim())
                    .header("APCA-API-SECRET-KEY", apiSecretKey.trim())
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
                log.warn("Alpaca news endpoint returned HTTP {} for symbol {}", statusCode, canonical);
                return Collections.emptyList();
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode newsArray = root.path("news");
            if (!newsArray.isArray() || newsArray.isEmpty()) {
                return Collections.emptyList();
            }

            List<NewsArticleDto> articles = new ArrayList<>();
            for (JsonNode item : newsArray) {
                String headline = item.path("headline").asText("").trim();
                String summary = item.path("summary").asText("").trim();
                String articleUrl = item.path("url").asText("").trim();
                String createdAt = item.path("created_at").asText("").trim();
                if (!headline.isBlank()) {
                    articles.add(new NewsArticleDto(headline, summary, articleUrl, createdAt, PROVIDER_NAME));
                }
            }
            return articles;
        } catch (Exception e) {
            log.warn("Lỗi khi tải tin tức từ Alpaca cho mã {}: {}", canonical, e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private void validateConfigured() {
        if (apiKeyId == null || apiKeyId.isBlank() || apiKeyId.startsWith("${") ||
                apiSecretKey == null || apiSecretKey.isBlank() || apiSecretKey.startsWith("${")) {
            throw new MarketDataUnavailableException("Chưa cấu hình API credentials cho Alpaca");
        }
    }

    private void checkCooldown(String symbol) {
        Long until = cooldownMap.get(symbol);
        if (until != null && System.currentTimeMillis() < until) {
            throw new MarketDataUnavailableException("Mã " + symbol + " đang trong thời gian chờ (cooldown) của Alpaca");
        }
    }

    private String cleanBaseUrl() {
        String url = (dataBaseUrl != null && !dataBaseUrl.isBlank()) ? dataBaseUrl.trim() : DEFAULT_DATA_URL;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private CandleDto parseBarNode(JsonNode bar, String timeframe) {
        if (!bar.hasNonNull("o") || !bar.hasNonNull("h") || !bar.hasNonNull("l") ||
                !bar.hasNonNull("c") || !bar.hasNonNull("v") || !bar.hasNonNull("t")) {
            return null;
        }

        BigDecimal open = BigDecimal.valueOf(bar.path("o").asDouble()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal high = BigDecimal.valueOf(bar.path("h").asDouble()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal low = BigDecimal.valueOf(bar.path("l").asDouble()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal close = BigDecimal.valueOf(bar.path("c").asDouble()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal volume = BigDecimal.valueOf(bar.path("v").asDouble()).setScale(2, RoundingMode.HALF_UP);

        if (open.compareTo(BigDecimal.ZERO) <= 0 || high.compareTo(BigDecimal.ZERO) <= 0 ||
                low.compareTo(BigDecimal.ZERO) <= 0 || close.compareTo(BigDecimal.ZERO) <= 0 ||
                volume.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }

        if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0 ||
                low.compareTo(open) > 0 || low.compareTo(close) > 0) {
            return null;
        }

        String rawTime = bar.path("t").asText();
        String formattedTime = formatTimestamp(rawTime, timeframe);

        return new CandleDto(formattedTime, open, high, low, close, volume);
    }

    private String formatTimestamp(String rawTime, String timeframe) {
        try {
            OffsetDateTime odt = OffsetDateTime.parse(rawTime);
            if ("1Min".equalsIgnoreCase(timeframe)) {
                return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } else {
                return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }
        } catch (Exception e) {
            return rawTime;
        }
    }
}
