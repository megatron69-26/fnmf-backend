package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.market.CandleDto;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Component
public class BinanceMarketClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceMarketClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${market.binance.rest-base-url:https://data-api.binance.vision}")
    private String primaryRestBaseUrl = "https://data-api.binance.vision";

    @Value("${market.binance.secondary-rest-base-url:https://api-gcp.binance.com}")
    private String secondaryRestBaseUrl = "https://api-gcp.binance.com";

    public record BinanceTickerResult(
            BigDecimal price,
            BigDecimal change24h,
            BigDecimal bidPrice,
            BigDecimal askPrice
    ) {}

    public static class NonRetryableMarketException extends IllegalStateException {
        private final int statusCode;

        public NonRetryableMarketException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    public BinanceMarketClient(ObjectMapper objectMapper) {
        this(objectMapper, "https://data-api.binance.vision", "https://api-gcp.binance.com");
    }

    @Autowired
    public BinanceMarketClient(
            ObjectMapper objectMapper,
            @Value("${market.binance.rest-base-url:https://data-api.binance.vision}") String primaryRestBaseUrl,
            @Value("${market.binance.secondary-rest-base-url:https://api-gcp.binance.com}") String secondaryRestBaseUrl) {
        this.objectMapper = objectMapper;
        this.primaryRestBaseUrl = primaryRestBaseUrl;
        this.secondaryRestBaseUrl = secondaryRestBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public BinanceMarketClient(ObjectMapper objectMapper, HttpClient httpClient, String primaryRestBaseUrl, String secondaryRestBaseUrl) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.primaryRestBaseUrl = primaryRestBaseUrl;
        this.secondaryRestBaseUrl = secondaryRestBaseUrl;
    }

    public String getPrimaryRestBaseUrl() {
        return normalizeBaseUrl(primaryRestBaseUrl);
    }

    public void setPrimaryRestBaseUrl(String primaryRestBaseUrl) {
        this.primaryRestBaseUrl = primaryRestBaseUrl;
    }

    public String getSecondaryRestBaseUrl() {
        return normalizeBaseUrl(secondaryRestBaseUrl);
    }

    public void setSecondaryRestBaseUrl(String secondaryRestBaseUrl) {
        this.secondaryRestBaseUrl = secondaryRestBaseUrl;
    }

    public String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://data-api.binance.vision";
        }
        return url.trim().replaceAll("/+$", "");
    }

    public String buildTickerUrl(String baseUrl, String binanceSymbol) {
        return normalizeBaseUrl(baseUrl) + "/api/v3/ticker/24hr?symbol=" + binanceSymbol;
    }

    public String buildKlinesUrl(String baseUrl, String binanceSymbol, String interval, int limit) {
        String binanceInterval = (interval != null && !interval.equalsIgnoreCase("daily")) ? interval : "1d";
        int candleLimit = limit > 0 ? limit : 30;
        return String.format("%s/api/v3/klines?symbol=%s&interval=%s&limit=%d",
                normalizeBaseUrl(baseUrl), binanceSymbol, binanceInterval, candleLimit);
    }

    /**
     * Lấy ticker 24 giờ cho 1 symbol với cơ chế failover an toàn từ Primary sang Secondary.
     */
    public BinanceTickerResult fetch24hrTicker(String binanceSymbol) throws Exception {
        HttpResponse<String> response = executeWithFailover(
                "TICKER_24HR",
                binanceSymbol,
                baseUrl -> buildTickerUrl(baseUrl, binanceSymbol)
        );

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.has("lastPrice")) {
            throw new IllegalStateException("Invalid Binance response: missing lastPrice for " + binanceSymbol);
        }

        BigDecimal rate = new BigDecimal(root.path("lastPrice").asText()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal bid = root.has("bidPrice")
                ? new BigDecimal(root.path("bidPrice").asText()).setScale(2, RoundingMode.HALF_UP)
                : rate;
        BigDecimal ask = root.has("askPrice")
                ? new BigDecimal(root.path("askPrice").asText()).setScale(2, RoundingMode.HALF_UP)
                : rate;
        BigDecimal change24h = root.has("priceChangePercent")
                ? new BigDecimal(root.path("priceChangePercent").asText()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new BinanceTickerResult(rate, change24h, bid, ask);
    }

    /**
     * Lấy chuỗi nến klines thật từ Binance với failover an toàn.
     */
    public List<CandleDto> fetchKlines(String binanceSymbol, String interval, int limit) throws Exception {
        HttpResponse<String> response = executeWithFailover(
                "KLINES",
                binanceSymbol,
                baseUrl -> buildKlinesUrl(baseUrl, binanceSymbol, interval, limit)
        );

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray()) {
            throw new IllegalStateException("Invalid Binance klines response for " + binanceSymbol);
        }

        List<CandleDto> list = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (JsonNode kline : root) {
            long openTimeMs = kline.get(0).asLong();
            LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(openTimeMs), ZoneId.systemDefault());
            String timeStr = dateTime.format(formatter);

            BigDecimal open = new BigDecimal(kline.get(1).asText()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = new BigDecimal(kline.get(2).asText()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = new BigDecimal(kline.get(3).asText()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal close = new BigDecimal(kline.get(4).asText()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal volume = new BigDecimal(kline.get(5).asText()).setScale(2, RoundingMode.HALF_UP);

            list.add(new CandleDto(timeStr, open, high, low, close, volume));
        }

        return list;
    }

    /**
     * Thực thi HTTP request với cơ chế Failover:
     * - Primary: data-api.binance.vision
     * - Secondary: api-gcp.binance.com
     * Chỉ failover khi timeout, lỗi mạng DNS, HTTP 429, hoặc HTTP 5xx.
     * Log: provider, endpoint type, symbol, HTTP status, duration. Không log response body không kiểm soát.
     */
    private HttpResponse<String> executeWithFailover(
            String endpointType,
            String symbol,
            Function<String, String> urlBuilder) throws Exception {

        String primaryBase = normalizeBaseUrl(primaryRestBaseUrl);
        String secondaryBase = normalizeBaseUrl(secondaryRestBaseUrl);

        // 1. Thử Primary
        long startMs = System.currentTimeMillis();
        String primaryUrl = urlBuilder.apply(primaryBase);
        Exception primaryException = null;
        HttpResponse<String> response = null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(primaryUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - startMs;
            log.info("MARKET API CALL | provider=PRIMARY | endpoint={} | symbol={} | status={} | durationMs={}",
                    endpointType, symbol, response.statusCode(), durationMs);

            if (response.statusCode() == 200) {
                return response;
            }

            if (!shouldFailover(response.statusCode())) {
                log.warn("PRIMARY PROVIDER RETURNED NON-RETRYABLE STATUS {} FOR {} | No failover to secondary", response.statusCode(), symbol);
                throw new NonRetryableMarketException(
                        "Primary provider returned non-retryable HTTP " + response.statusCode() + " for " + symbol,
                        response.statusCode()
                );
            }
            log.warn("PRIMARY PROVIDER RETURNED STATUS {} FOR {} | Failing over to secondary", response.statusCode(), symbol);
        } catch (NonRetryableMarketException nrEx) {
            // Lỗi 400, 404... là lỗi không được retry, ném thẳng ra ngoài, tuyệt đối không failover
            throw nrEx;
        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - startMs;
            primaryException = ex;
            log.warn("PRIMARY PROVIDER FAILED | endpoint={} | symbol={} | durationMs={} | error={}",
                    endpointType, symbol, durationMs, ex.getMessage());
        }

        // 2. Thử Secondary đúng 1 lần nếu Primary lỗi mạng, timeout hoặc HTTP 429/5xx/403
        long secStartMs = System.currentTimeMillis();
        String secondaryUrl = urlBuilder.apply(secondaryBase);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(secondaryUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> secResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - secStartMs;
            log.info("MARKET API CALL | provider=SECONDARY | endpoint={} | symbol={} | status={} | durationMs={}",
                    endpointType, symbol, secResponse.statusCode(), durationMs);

            if (secResponse.statusCode() == 200) {
                return secResponse;
            }

            throw new IllegalStateException("Secondary provider returned HTTP " + secResponse.statusCode() + " for " + symbol);
        } catch (Exception secEx) {
            long durationMs = System.currentTimeMillis() - secStartMs;
            log.error("SECONDARY PROVIDER FAILED | endpoint={} | symbol={} | durationMs={} | error={}",
                    endpointType, symbol, durationMs, secEx.getMessage());

            if (primaryException != null) {
                throw new IllegalStateException("All market providers failed for " + symbol +
                        ". Primary: " + primaryException.getMessage() + ", Secondary: " + secEx.getMessage(), secEx);
            }
            throw secEx;
        }
    }

    private boolean shouldFailover(int statusCode) {
        return statusCode == 429 || statusCode == 403 || statusCode == 451 || (statusCode >= 500 && statusCode < 600);
    }
}
