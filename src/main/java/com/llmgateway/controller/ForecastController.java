package com.llmgateway.controller;

import com.llmgateway.dto.forecast.ForecastRequest;
import com.llmgateway.dto.forecast.ForecastResponse;
import com.llmgateway.entity.MarketForecast;
import com.llmgateway.service.ForecastService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    /**
     * GET /api/forecast/{symbol}?timeframe=24H_7D
     * Lấy phân tích dự báo xu hướng AI cho 1 mã tài sản (Ví dụ: BTCUSDT, ETHUSDT, XAUUSD, USOIL)
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<ForecastResponse> getForecastBySymbol(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "24H_7D") String timeframe) {
        ForecastRequest request = new ForecastRequest(symbol, timeframe);
        ForecastResponse response = forecastService.generateForecast(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/forecast/analyze
     * Tạo hoặc làm mới bản dự báo thị trường AI
     */
    @PostMapping("/analyze")
    public ResponseEntity<ForecastResponse> analyzeForecast(@Valid @RequestBody ForecastRequest request) {
        ForecastResponse response = forecastService.generateForecast(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/forecast/history/{symbol}
     * Lấy lịch sử các bản dự báo AI trong quá khứ của 1 mã tài sản
     */
    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<MarketForecast>> getForecastHistory(@PathVariable String symbol) {
        List<MarketForecast> history = forecastService.getForecastHistory(symbol);
        return ResponseEntity.ok(history);
    }

    /**
     * GET /api/forecast/latest
     * Lấy 10 bản dự báo AI mới nhất của toàn hệ thống
     */
    @GetMapping("/latest")
    public ResponseEntity<List<MarketForecast>> getLatestForecasts() {
        List<MarketForecast> latest = forecastService.getLatestForecasts();
        return ResponseEntity.ok(latest);
    }
}
