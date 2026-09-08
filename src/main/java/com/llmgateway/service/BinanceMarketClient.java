package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.market.CandleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
public class BinanceMarketClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceMarketClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public record BinanceTickerResult(
            BigDecimal price,
            BigDecimal change24h,
            BigDecimal bidPrice,
            BigDecimal askPrice
    ) {}

    public BinanceMarketClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    public BinanceTickerResult fetch24hrTicker(String binanceSymbol) throws Exception {
        String url = String.format("https://api.binance.com/api/v3/ticker/24hr?symbol=%s", binanceSymbol);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Binance API returned HTTP " + response.statusCode() + " for symbol " + binanceSymbol);
        }

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

    public List<CandleDto> fetchKlines(String binanceSymbol, String interval, int limit) throws Exception {
        String binanceInterval = (interval != null && !interval.equalsIgnoreCase("daily")) ? interval : "1d";
        int candleLimit = limit > 0 ? limit : 30;
        String url = String.format("https://api.binance.com/api/v3/klines?symbol=%s&interval=%s&limit=%d",
                binanceSymbol, binanceInterval, candleLimit);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Binance klines API returned HTTP " + response.statusCode() + " for symbol " + binanceSymbol);
        }

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
}
