package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.MarketSymbolConfig;
import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.dto.watchlist.WatchlistRequest;
import com.llmgateway.filter.RateLimitFilter;
import com.llmgateway.repository.WatchlistRepository;
import com.llmgateway.service.WatchlistService;
import com.llmgateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Context & MockMvc Integration Test:
 * 1. Kiểm tra 5 mã mới qua Service: Add -> GET -> Idempotent Add -> Remove.
 * 2. Kiểm tra MockMvc chuỗi request thật qua Filter & Controller:
 *    - 8 mã thêm liên tiếp không bị chặn HTTP 429.
 *    - /api/watchlist/ai-insights VẪN BỊ GIỚI HẠN CHẶT CHẼ (vượt quá 10 req/phút trả về HTTP 429).
 *    - Watchlist CRUD vẫn được bảo vệ bởi hạn mức method riêng (vượt 30 req/phút trả về HTTP 429).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_watchlist_5symbols_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "gateway.rate-limit.max-requests=10",
        "gateway.rate-limit.watchlist.get-max=60",
        "gateway.rate-limit.watchlist.mutation-max=30",
        "alphavantage.api.key=test_api_key",
        "openai.api.key=test_api_key"
})
public class WatchlistFiveNewSymbolsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WatchlistService watchlistService;

    @Autowired
    private WatchlistRepository watchlistRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private ObjectMapper objectMapper;

    private final Long testUserId = 8888L;
    private String validAuthHeader;

    @BeforeEach
    public void setup() {
        watchlistRepository.deleteAll();
        rateLimitFilter.clearCounters();
        String token = jwtUtil.generateToken("testuser8888@example.com", testUserId);
        validAuthHeader = "Bearer " + token;
    }

    @Test
    @DisplayName("Chu trình Add -> GET -> Add lần 2 Idempotent -> Remove cho 5 mã mới qua Service")
    public void testFiveNewSymbolsLifecycle() {
        String[] fiveSymbols = {"BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT"};

        for (String sym : fiveSymbols) {
            // 1. Thêm mã vào watchlist
            WatchlistItemDto added = watchlistService.addToWatchlist(testUserId, new WatchlistRequest(sym, 1));
            assertNotNull(added, "Kết quả add không được null cho " + sym);
            assertEquals(sym, added.getSymbol(), "Symbol trả về phải là canonical: " + sym);

            // 2. GET xác nhận mã có trong watchlist
            List<WatchlistItemDto> currentList = watchlistService.getUserWatchlist(testUserId);
            assertTrue(currentList.stream().anyMatch(i -> sym.equals(i.getSymbol())),
                    "Watchlist phải chứa mã " + sym);

            // 3. Add lần 2 (Idempotent check)
            WatchlistItemDto duplicateAdd = watchlistService.addToWatchlist(testUserId, new WatchlistRequest(sym, 1));
            assertNotNull(duplicateAdd);
            assertEquals(added.getId(), duplicateAdd.getId(), "Idempotent: ID bản ghi phải trùng khớp");

            // Xác minh DB không bị duplicate
            long countInDb = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(testUserId)
                    .stream().filter(w -> sym.equals(w.getSymbol())).count();
            assertEquals(1L, countInDb, "Không được có quá 1 bản ghi trong DB cho " + sym);

            // 4. Xóa mã khỏi watchlist
            watchlistService.removeFromWatchlist(testUserId, sym);

            // 5. GET xác nhận mã không còn
            List<WatchlistItemDto> afterRemove = watchlistService.getUserWatchlist(testUserId);
            assertFalse(afterRemove.stream().anyMatch(i -> sym.equals(i.getSymbol())),
                    "Watchlist không được còn chứa mã " + sym + " sau khi remove");
        }
    }

    @Test
    @DisplayName("Hỗ trợ Alias cho 5 mã mới và các mã cốt lõi nhưng luôn lưu Canonical")
    public void testAliasesAlwaysSaveCanonical() {
        // Alias BNB -> BNBUSDT
        WatchlistItemDto bnb = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("BNB/USDT", 1));
        assertEquals("BNBUSDT", bnb.getSymbol());

        // Alias SOL -> SOLUSDT
        WatchlistItemDto sol = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("SOL", 2));
        assertEquals("SOLUSDT", sol.getSymbol());

        // Alias XRP -> XRPUSDT
        WatchlistItemDto xrp = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("XRP/USDT", 3));
        assertEquals("XRPUSDT", xrp.getSymbol());

        // Alias ADA -> ADAUSDT
        WatchlistItemDto ada = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("ADA", 4));
        assertEquals("ADAUSDT", ada.getSymbol());

        // Alias DOGE -> DOGEUSDT
        WatchlistItemDto doge = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("DOGE/USDT", 5));
        assertEquals("DOGEUSDT", doge.getSymbol());

        // Alias XAU -> XAUUSD (PAXGUSDT)
        WatchlistItemDto xau = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("PAXG/USDT", 6));
        assertEquals("XAUUSD", xau.getSymbol());

        // Kiểm tra danh sách User Watchlist
        List<WatchlistItemDto> items = watchlistService.getUserWatchlist(testUserId);
        assertEquals(6, items.size());
        assertTrue(items.stream().allMatch(i -> MarketSymbolConfig.isSupported(i.getSymbol())));

        // Thêm lại bằng alias khác không tạo duplicate
        WatchlistItemDto bnbDup = watchlistService.addToWatchlist(testUserId, new WatchlistRequest("BNB", 1));
        assertEquals(bnb.getId(), bnbDup.getId());

        // Xóa bằng alias thành công
        watchlistService.removeFromWatchlist(testUserId, "SOL/USDT");
        List<WatchlistItemDto> afterSolRemove = watchlistService.getUserWatchlist(testUserId);
        assertEquals(5, afterSolRemove.size());
        assertFalse(afterSolRemove.stream().anyMatch(i -> "SOLUSDT".equals(i.getSymbol())));
    }

    // =====================================================================
    // MOCKMVC & FILTER TESTS: RATE LIMIT VERIFICATION
    // =====================================================================

    @Test
    @DisplayName("MockMvc: Thêm 8 mã liên tiếp và gọi GET/DELETE không bị chặn bởi HTTP 429")
    public void testMockMvc_rapidWatchlistCrud_doesNotHit429() throws Exception {
        String[] allEightSymbols = {
                "BTCUSDT", "ETHUSDT", "XAUUSD", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT"
        };

        // 1. Thao tác 8 POST liên tiếp qua Filter + Controller
        for (String sym : allEightSymbols) {
            String payload = objectMapper.writeValueAsString(new WatchlistRequest(sym, 1));
            mockMvc.perform(post("/api/watchlist")
                            .header("Authorization", validAuthHeader)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value(sym));
        }

        // 2. Thao tác 8 GET liên tiếp qua Filter + Controller
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(get("/api/watchlist")
                            .header("Authorization", validAuthHeader))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(8));
        }

        // 3. Thao tác 8 DELETE liên tiếp qua Filter + Controller
        for (String sym : allEightSymbols) {
            mockMvc.perform(delete("/api/watchlist/" + sym)
                            .header("Authorization", validAuthHeader))
                    .andExpect(status().isOk());
        }

        // 4. GET cuối cùng xác nhận danh sách trống
        mockMvc.perform(get("/api/watchlist")
                        .header("Authorization", validAuthHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("MockMvc: /api/watchlist/ai-insights VẪN BỊ GIỚI HẠN CHẶT CHẼ và trả về HTTP 429 sau 10 request")
    public void testMockMvc_aiInsights_remainsStrictlyRateLimited() throws Exception {
        // Gọi 10 request đầu tiên tới /api/watchlist/ai-insights
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(get("/api/watchlist/ai-insights")
                            .header("Authorization", validAuthHeader))
                    .andExpect(status().isOk());
        }

        // Request thứ 11 VƯỢT QUÁ giới hạn 10 req/phút -> PHẢI TRẢ VỀ HTTP 429
        mockMvc.perform(get("/api/watchlist/ai-insights")
                        .header("Authorization", validAuthHeader))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("MockMvc: Watchlist POST vượt quá hạn mức method (30 req) vẫn bị chặn HTTP 429")
    public void testMockMvc_watchlistPostSpam_hitsRateLimitAtThreshold() throws Exception {
        String payload = objectMapper.writeValueAsString(new WatchlistRequest("BTCUSDT", 1));

        // 30 request POST hợp lệ
        for (int i = 1; i <= 30; i++) {
            mockMvc.perform(post("/api/watchlist")
                            .header("Authorization", validAuthHeader)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk());
        }

        // Request thứ 31 vượt quá 30 req/phút -> PHẢI TRẢ VỀ HTTP 429
        mockMvc.perform(post("/api/watchlist")
                        .header("Authorization", validAuthHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.status").value(429));
    }
}
