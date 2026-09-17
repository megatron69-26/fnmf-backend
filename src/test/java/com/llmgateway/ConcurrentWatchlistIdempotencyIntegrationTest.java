package com.llmgateway;

import com.llmgateway.dto.watchlist.WatchlistItemDto;
import com.llmgateway.dto.watchlist.WatchlistRequest;
import com.llmgateway.entity.Watchlist;
import com.llmgateway.repository.WatchlistRepository;
import com.llmgateway.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent Integration Test cho Watchlist (Blocker 1 Fix):
 * Kiểm thử tính Idempotent và chống Race Condition trên cơ sở dữ liệu thật (in-memory H2 PostgreSQL mode).
 * Xác minh 2 request đồng thời thêm cùng mã (hoặc alias) vào watchlist:
 * - Không bị UnexpectedRollbackException do lỗi transaction rollback-only.
 * - Cả 2 luồng đều nhận kết quả WatchlistItemDto hợp lệ.
 * - CSDL chỉ lưu duy nhất 1 bản ghi nhờ unique constraint và isolated transaction (REQUIRES_NEW).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:fnmf_concurrent_watchlist_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=LocalTestContextJwtSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security12345",
        "alphavantage.api.key=test_api_key",
        "openai.api.key=test_api_key"
})
public class ConcurrentWatchlistIdempotencyIntegrationTest {

    @Autowired
    private WatchlistService watchlistService;

    @Autowired
    private WatchlistRepository watchlistRepository;

    private final Long testUserId = 9999L;

    @BeforeEach
    public void setup() {
        watchlistRepository.deleteAll();
    }

    @Test
    @DisplayName("Blocker 1: Hai request đồng thời thêm cùng symbol không ném UnexpectedRollbackException và chỉ lưu 1 bản ghi")
    public void testConcurrentAddToWatchlist_sameSymbol_succeedsWithoutRollbackException() throws Exception {
        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<WatchlistItemDto>> tasks = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                // Chờ cả 2 luồng sẵn sàng để kích hoạt đồng thời
                if (!startLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout chờ startLatch");
                }
                WatchlistRequest request = new WatchlistRequest();
                request.setSymbol("BNBUSDT");
                request.setDisplayOrder(1);
                return watchlistService.addToWatchlist(testUserId, request);
            });
        }

        List<Future<WatchlistItemDto>> results = new ArrayList<>();
        for (Callable<WatchlistItemDto> task : tasks) {
            results.add(executor.submit(task));
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Cả 2 luồng phải sẵn sàng tranh chấp");
        startLatch.countDown(); // Bắn đồng thời 2 luồng cùng lúc

        // Cả 2 luồng phải hoàn thành thành công mà không ném ExecutionException (UnexpectedRollbackException)
        for (Future<WatchlistItemDto> future : results) {
            WatchlistItemDto dto = future.get(10, TimeUnit.SECONDS);
            assertNotNull(dto, "WatchlistItemDto không được null");
            assertEquals("BNBUSDT", dto.getSymbol(), "Symbol phải là BNBUSDT");
        }

        executor.shutdown();

        // Kiểm tra database: chỉ có ĐÚNG 1 bản ghi duy nhất
        List<Watchlist> dbItems = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(testUserId);
        assertEquals(1, dbItems.size(), "CSDL chỉ được chứa duy nhất 1 bản ghi cho BNBUSDT");
        assertEquals("BNBUSDT", dbItems.get(0).getSymbol());
    }

    @Test
    @DisplayName("Blocker 1: Hai request đồng thời với alias khác nhau (BTC và BTC/USDT) đều chuẩn hóa về BTCUSDT và chỉ lưu 1 bản ghi")
    public void testConcurrentAddToWatchlist_aliases_succeedsAndDeduplicates() throws Exception {
        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        String[] symbols = new String[]{"BTC", "BTC/USDT"};

        List<Callable<WatchlistItemDto>> tasks = new ArrayList<>();
        for (int i = 0; i < numberOfThreads; i++) {
            final String sym = symbols[i];
            tasks.add(() -> {
                readyLatch.countDown();
                if (!startLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout chờ startLatch");
                }
                WatchlistRequest request = new WatchlistRequest();
                request.setSymbol(sym);
                request.setDisplayOrder(2);
                return watchlistService.addToWatchlist(testUserId, request);
            });
        }

        List<Future<WatchlistItemDto>> results = new ArrayList<>();
        for (Callable<WatchlistItemDto> task : tasks) {
            results.add(executor.submit(task));
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Cả 2 luồng alias phải sẵn sàng tranh chấp");
        startLatch.countDown(); // Bắn đồng thời 2 luồng cùng lúc

        for (Future<WatchlistItemDto> future : results) {
            WatchlistItemDto dto = future.get(10, TimeUnit.SECONDS);
            assertNotNull(dto);
            assertEquals("BTCUSDT", dto.getSymbol());
        }

        executor.shutdown();

        List<Watchlist> dbItems = watchlistRepository.findByUserIdOrderByDisplayOrderAsc(testUserId);
        assertEquals(1, dbItems.size(), "CSDL chỉ được chứa duy nhất 1 bản ghi cho BTCUSDT khi truyền các alias");
        assertEquals("BTCUSDT", dbItems.get(0).getSymbol());
    }
}
