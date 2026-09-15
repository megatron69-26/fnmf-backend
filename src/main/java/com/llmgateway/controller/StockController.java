package com.llmgateway.controller;

import com.llmgateway.dto.stock.StockCatalogDto;
import com.llmgateway.dto.stock.StockDetailDto;
import com.llmgateway.service.StockMarketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockMarketService stockMarketService;

    public StockController(StockMarketService stockMarketService) {
        this.stockMarketService = stockMarketService;
    }

    /**
     * GET /api/stocks
     * Đọc danh sách 8 mã cổ phiếu gồm symbol và tên doanh nghiệp.
     * Metadata cố định, không tự động gọi provider cho cả 8 mã khi chỉ mở danh mục.
     */
    @GetMapping
    public ResponseEntity<List<StockCatalogDto>> getStockCatalog() {
        return ResponseEntity.ok(stockMarketService.getStockCatalog());
    }

    /**
     * GET /api/stocks/{symbol}
     * Lấy thông tin chi tiết của 1 mã cổ phiếu: giá, thời điểm giá, khuyến nghị, bài báo thật.
     * Chỉ tải dữ liệu cho đúng mã được yêu cầu.
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<StockDetailDto> getStockDetail(@PathVariable String symbol) {
        return ResponseEntity.ok(stockMarketService.getStockDetail(symbol));
    }
}
