package com.llmgateway;

import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.market.MarketPriceDto;
import com.llmgateway.dto.trade.OrderRequest;
import com.llmgateway.dto.trade.OrderResponse;
import com.llmgateway.entity.Holding;
import com.llmgateway.entity.Transaction;
import com.llmgateway.entity.Wallet;
import com.llmgateway.exception.IdempotencyConflictException;
import com.llmgateway.exception.MarketDataUnavailableException;
import com.llmgateway.exception.UnsupportedSymbolException;
import com.llmgateway.repository.HoldingRepository;
import com.llmgateway.repository.TransactionRepository;
import com.llmgateway.repository.WalletRepository;
import com.llmgateway.service.BinanceMarketClient;
import com.llmgateway.service.MarketDataService;
import com.llmgateway.service.TradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class MarketSymbolAndTradeIntegrityTest {

    // =========================================================================
    // 1. KIỂM THỬ CANONICAL SYMBOLS & TỪ CHỐI USOIL
    // =========================================================================

    @Test
    @DisplayName("MarketSymbolConfig chỉ hỗ trợ BTCUSDT, ETHUSDT, XAUUSD và từ chối USOIL")
    public void testMarketSymbolConfig_whitelistAndRejections() {
        // Hợp lệ
        assertTrue(MarketSymbolConfig.isSupported("BTCUSDT"));
        assertTrue(MarketSymbolConfig.isSupported("ETHUSDT"));
        assertTrue(MarketSymbolConfig.isSupported("XAUUSD"));
        assertTrue(MarketSymbolConfig.isSupported("BTC/USDT"));
        assertTrue(MarketSymbolConfig.isSupported("btcusdt"));

        // Không hỗ trợ: USOIL, SPX, AAPL
        assertFalse(MarketSymbolConfig.isSupported("USOIL"));
        assertFalse(MarketSymbolConfig.isSupported("WTI"));
        assertFalse(MarketSymbolConfig.isSupported("SPX"));
        assertFalse(MarketSymbolConfig.isSupported("AAPL"));

        // Validate ném ngoại lệ đúng
        assertThrows(UnsupportedSymbolException.class, () -> MarketSymbolConfig.validateSupported("USOIL"));
        assertThrows(UnsupportedSymbolException.class, () -> MarketSymbolConfig.validateSupported("UNKNOWN"));
    }

    @Test
    @DisplayName("MarketSymbolConfig chuẩn hóa symbol hợp lệ sang Binance symbol và từ chối mã không hợp lệ")
    public void testMarketSymbolConfig_toBinanceSymbol() {
        assertEquals("BTCUSDT", MarketSymbolConfig.getBinanceSymbol("BTCUSDT"));
        assertEquals("ETHUSDT", MarketSymbolConfig.getBinanceSymbol("ETHUSDT"));
        assertEquals("PAXGUSDT", MarketSymbolConfig.getBinanceSymbol("XAUUSD"));

        assertThrows(UnsupportedSymbolException.class, () -> MarketSymbolConfig.getBinanceSymbol("USOIL"));
    }

    // =========================================================================
    // 2. KIỂM THỬ KHÔNG FALLBACK VÀ KHÔNG RANDOM NẾN
    // =========================================================================

    @Test
    @DisplayName("MarketDataService từ chối USOIL và quăng UnsupportedSymbolException, không fallback sang BTC")
    public void testMarketDataService_rejectsUsoilWithoutFallback() {
        BinanceMarketClient mockBinance = mock(BinanceMarketClient.class);
        MarketDataService marketDataService = new MarketDataService(new com.fasterxml.jackson.databind.ObjectMapper(), mockBinance);

        assertThrows(UnsupportedSymbolException.class, () -> marketDataService.getPriceBySymbol("USOIL"));
        assertThrows(UnsupportedSymbolException.class, () -> marketDataService.getCandles("USOIL", "1h"));
    }

    // =========================================================================
    // 3. KIỂM THỬ TÍNH TOÀN VẸN GIAO DỊCH, KHÓA BI QUAN & IDEMPOTENCY
    // =========================================================================

    @Test
    @DisplayName("TradeService từ chối khớp lệnh khi giá null, <= 0 hoặc stale=true")
    public void testTradeService_rejectsInvalidOrStalePrice() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);

        // Giá null
        assertThrows(MarketDataUnavailableException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, "uuid-valid-1"), null));

        // Giá <= 0
        MarketPriceDto zeroPrice = new MarketPriceDto("BTCUSDT", "BTC", "CRYPTO", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");
        assertThrows(MarketDataUnavailableException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, "uuid-valid-2"), zeroPrice));

        // Giá stale = true
        MarketPriceDto stalePrice = new MarketPriceDto("BTCUSDT", "BTC", "CRYPTO", new BigDecimal("68000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");
        stalePrice.setStale(true);
        assertThrows(MarketDataUnavailableException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, "uuid-valid-3"), stalePrice));

        // Symbol USOIL
        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "BTC", "CRYPTO", new BigDecimal("68000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");
        assertThrows(UnsupportedSymbolException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("USOIL", "BUY", BigDecimal.ONE, "uuid-valid-4"), priceDto));
    }

    @Test
    @DisplayName("TradeService khớp lệnh MUA thành công: sử dụng khóa bi quan, trừ tiền, cập nhật holding và lưu clientOrderId")
    public void testTradeService_executeBuyOrder_success() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Wallet wallet = new Wallet(1L, new BigDecimal("10000.0000"));
        wallet.setId(10L);

        when(mockWalletRepo.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(mockWalletRepo.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(mockTxRepo.findByWalletIdAndClientOrderId(10L, "uuid-1234")).thenReturn(Optional.empty());
        when(mockHoldingRepo.findByWalletIdAndSymbolForUpdate(10L, "BTCUSDT")).thenReturn(Optional.empty());
        when(mockTxRepo.saveAndFlush(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);

        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO", new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");
        OrderRequest request = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.1"), "uuid-1234");

        OrderResponse response = tradeService.executeOrder(1L, request, priceDto);

        assertNotNull(response);
        assertEquals("BTCUSDT", response.getSymbol());
        assertEquals("BUY", response.getType());
        assertEquals(new BigDecimal("50000.00"), response.getPrice());
        assertEquals(new BigDecimal("0.1"), response.getQuantity());
        assertEquals(new BigDecimal("5000.0000"), response.getTotalAmount());
        assertEquals(new BigDecimal("5000.0000"), response.getRemainingBalance());

        // Kiểm tra Transaction được lưu với clientOrderId
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(mockTxRepo).saveAndFlush(txCaptor.capture());
        Transaction savedTx = txCaptor.getValue();
        assertEquals("uuid-1234", savedTx.getClientOrderId());
        assertEquals("BTCUSDT", savedTx.getSymbol());
        assertEquals("BUY", savedTx.getType());

        // Kiểm tra Holding được tạo
        ArgumentCaptor<Holding> holdingCaptor = ArgumentCaptor.forClass(Holding.class);
        verify(mockHoldingRepo).save(holdingCaptor.capture());
        Holding savedHolding = holdingCaptor.getValue();
        assertEquals(new BigDecimal("0.1"), savedHolding.getQuantity());
        assertEquals(new BigDecimal("50000.00"), savedHolding.getAvgBuyPrice());

        // Xác minh gọi findByUserIdForUpdate (Pessimistic Lock)
        verify(mockWalletRepo).findByUserIdForUpdate(1L);
        verify(mockHoldingRepo).findByWalletIdAndSymbolForUpdate(10L, "BTCUSDT");
    }

    @Test
    @DisplayName("Idempotency: Khi gửi lại lệnh cùng clientOrderId và payload, hệ thống replay kết quả mà KHÔNG trừ tiền thêm lần 2")
    public void testTradeService_idempotentOrderReplay() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Wallet wallet = new Wallet(1L, new BigDecimal("5000.0000"));
        wallet.setId(10L);

        Transaction existingTx = new Transaction(10L, "BTCUSDT", "BUY", new BigDecimal("50000.00"), new BigDecimal("0.1"), new BigDecimal("5000.0000"), "uuid-repeat-123");
        existingTx.setId(999L);

        when(mockWalletRepo.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(mockWalletRepo.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(mockTxRepo.findByWalletIdAndClientOrderId(10L, "uuid-repeat-123")).thenReturn(Optional.of(existingTx));

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);

        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "Bitcoin", "CRYPTO", new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");
        OrderRequest request = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.1"), "uuid-repeat-123");

        OrderResponse response = tradeService.executeOrder(1L, request, priceDto);

        assertNotNull(response);
        assertEquals(999L, response.getTransactionId());
        assertEquals(new BigDecimal("5000.0000"), response.getRemainingBalance());
        assertTrue(response.getMessage().contains("Idempotent replay"));

        // Tuyệt đối KHÔNG trừ tiền ví và KHÔNG lưu thêm Transaction mới
        assertEquals(new BigDecimal("5000.0000"), wallet.getBalanceUsd(), "Số dư ví không được thay đổi");
        verify(mockTxRepo, never()).saveAndFlush(any(Transaction.class));
        verify(mockHoldingRepo, never()).save(any(Holding.class));
    }

    @Test
    @DisplayName("Idempotency: Cùng clientOrderId nhưng thay đổi payload (BUY sang SELL hoặc đổi số lượng) bị từ chối với HTTP 409 Conflict")
    public void testTradeService_rejectsIdempotencyKeyReusedWithDifferentPayload() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Wallet wallet = new Wallet(1L, new BigDecimal("5000.0000"));
        wallet.setId(10L);

        Transaction existingTx = new Transaction(10L, "BTCUSDT", "BUY", new BigDecimal("50000.00"), new BigDecimal("0.1"), new BigDecimal("5000.0000"), "order-key-1");
        existingTx.setId(100L);

        when(mockWalletRepo.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(mockTxRepo.findByWalletIdAndClientOrderId(10L, "order-key-1")).thenReturn(Optional.of(existingTx));

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);
        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "BTC", "CRYPTO", new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");

        // Thay đổi quantity: 0.1 -> 0.2
        OrderRequest reqDifferentQty = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.2"), "order-key-1");
        IdempotencyConflictException ex1 = assertThrows(IdempotencyConflictException.class, () ->
                tradeService.executeOrder(1L, reqDifferentQty, priceDto));
        assertTrue(ex1.getMessage().contains("different payload"));

        // Thay đổi orderType: BUY -> SELL
        OrderRequest reqDifferentType = new OrderRequest("BTCUSDT", "SELL", new BigDecimal("0.1"), "order-key-1");
        IdempotencyConflictException ex2 = assertThrows(IdempotencyConflictException.class, () ->
                tradeService.executeOrder(1L, reqDifferentType, priceDto));
        assertTrue(ex2.getMessage().contains("different payload"));
    }

    // =========================================================================
    // 4. KIỂM THỬ FLYWAY MIGRATION V3 FILE INTEGRITY
    // =========================================================================

    @Test
    @DisplayName("Flyway Migration V3 file tồn tại và chứa đầy đủ logic kiểm tra trùng lặp và client_order_id")
    public void testFlywayMigrationV3_fileContent() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("db/migration/V3__holding_unique_constraint_and_client_order_id.sql")) {
            assertNotNull(is, "File V3 migration phải tồn tại trên classpath");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(sql.contains("uk_holding_wallet_symbol"), "V3 phải chứa constraint uk_holding_wallet_symbol");
            assertTrue(sql.contains("client_order_id"), "V3 phải thêm cột client_order_id");
            assertTrue(sql.contains("uk_transactions_wallet_client_order_id"), "V3 phải chứa unique index uk_transactions_wallet_client_order_id");
            assertTrue(sql.contains("duplicate holding(s) detected"), "V3 phải có bước pre-check duplicate holdings");
        }
    }

    @Test
    @DisplayName("TradeService từ chối lệnh khi clientOrderId bị null, blank, quá dài hoặc chứa ký tự đặc biệt nguy hiểm")
    public void testTradeService_rejectsInvalidClientOrderId() {
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Wallet wallet = new Wallet(1L, new BigDecimal("10000.0000"));
        wallet.setId(10L);
        when(mockWalletRepo.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(mockWalletRepo.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);
        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "BTC", "CRYPTO", new BigDecimal("68000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");

        // Null hoặc Blank clientOrderId
        IllegalArgumentException exNull = assertThrows(IllegalArgumentException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, null), priceDto));
        assertTrue(exNull.getMessage().contains("clientOrderId"), "Lỗi phải chỉ rõ thiếu clientOrderId: " + exNull.getMessage());

        IllegalArgumentException exBlank = assertThrows(IllegalArgumentException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, "   "), priceDto));
        assertTrue(exBlank.getMessage().contains("clientOrderId"), "Lỗi phải chỉ rõ clientOrderId bị trống: " + exBlank.getMessage());

        // Quá 64 ký tự
        String longId = "a".repeat(65);
        IllegalArgumentException exLong = assertThrows(IllegalArgumentException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, longId), priceDto));
        assertTrue(exLong.getMessage().contains("clientOrderId"), "Lỗi phải chỉ rõ clientOrderId quá dài: " + exLong.getMessage());

        // Ký tự không hợp lệ (chứa space, dấu nháy, ký tự đặc biệt)
        IllegalArgumentException exSpace = assertThrows(IllegalArgumentException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, "id with spaces!"), priceDto));
        assertTrue(exSpace.getMessage().contains("clientOrderId"), "Lỗi phải chỉ rõ clientOrderId không hợp lệ: " + exSpace.getMessage());

        IllegalArgumentException exSql = assertThrows(IllegalArgumentException.class, () ->
                tradeService.executeOrder(1L, new OrderRequest("BTCUSDT", "BUY", BigDecimal.ONE, "id' OR '1'='1"), priceDto));
        assertTrue(exSql.getMessage().contains("clientOrderId"), "Lỗi phải chỉ rõ clientOrderId không hợp lệ: " + exSql.getMessage());
    }

    @Test
    @DisplayName("Serialization & Scale: Backend nhận đúng BigDecimal và từ chối scale > 6")
    public void testOrderRequest_jsonSerializationAndScaleValidation() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // 1. JSON từ Android client với trailing zeros: 0.050000
        String jsonWithTrailingZeros = "{\"symbol\":\"BTCUSDT\",\"type\":\"BUY\",\"quantity\":0.050000,\"clientOrderId\":\"test-uuid-scale\"}";
        OrderRequest parsed = mapper.readValue(jsonWithTrailingZeros, OrderRequest.class);

        assertNotNull(parsed);
        assertEquals("BTCUSDT", parsed.getSymbol());
        assertEquals("BUY", parsed.getType());
        assertEquals("test-uuid-scale", parsed.getClientOrderId());
        assertEquals(0, parsed.getQuantity().compareTo(new BigDecimal("0.05")));

        // 2. Kiểm tra chính sách scale <= 6
        WalletRepository mockWalletRepo = mock(WalletRepository.class);
        HoldingRepository mockHoldingRepo = mock(HoldingRepository.class);
        TransactionRepository mockTxRepo = mock(TransactionRepository.class);
        MarketDataService mockMarketData = mock(MarketDataService.class);

        Wallet wallet = new Wallet(1L, new BigDecimal("10000.0000"));
        wallet.setId(10L);
        when(mockWalletRepo.findByUserId(1L)).thenReturn(Optional.of(wallet));
        when(mockWalletRepo.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        TradeService tradeService = new TradeService(mockWalletRepo, mockHoldingRepo, mockTxRepo, mockMarketData);
        MarketPriceDto priceDto = new MarketPriceDto("BTCUSDT", "BTC", "CRYPTO", new BigDecimal("68000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "now");

        // Hợp lệ: 6 chữ số thập phân
        OrderRequest reqScale6 = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.123456"), "valid-scale-6");
        // Không ném IllegalArgumentException về scale
        assertDoesNotThrow(() -> {
            try {
                tradeService.executeOrder(1L, reqScale6, priceDto);
            } catch (NullPointerException | org.springframework.dao.DataIntegrityViolationException ignored) {
                // Ignore downstream mock calls
            }
        });

        // Bị từ chối: 7 chữ số thập phân (scale > 6)
        OrderRequest reqScale7 = new OrderRequest("BTCUSDT", "BUY", new BigDecimal("0.1234567"), "invalid-scale-7");
        IllegalArgumentException exScale7 = assertThrows(IllegalArgumentException.class, () ->
                tradeService.executeOrder(1L, reqScale7, priceDto));
        assertTrue(exScale7.getMessage().contains("tối đa 6 chữ số thập phân"), "Phải từ chối scale > 6: " + exScale7.getMessage());
    }
}
