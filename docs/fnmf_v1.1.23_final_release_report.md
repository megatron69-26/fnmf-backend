# Báo Cáo Phát Hành Chính Thức FNMF v1.1.23 (Fixed Provider Sharding & Gemini Shard Routing)

- **Phiên bản:** `v1.1.23`
- **Thời gian hoàn tất:** 2026-09-16
- **Người thực hiện:** Đặng Đức Khôi (Backend, Data Pipeline & Android Integration)
- **Production Backend:** `https://fnmf-backend-production.up.railway.app/`
- **Database:** PostgreSQL trên Railway (Đã áp dụng Migration V1 - V10)

---

## 1. Mục Tiêu & Kiến Trúc Phiên Bản v1.1.23

Phiên bản v1.1.23 giải quyết triệt để bài toán hạn ngạch nhà cung cấp (rate limit / quota), loại bỏ phụ thuộc vào Alpha Vantage free tier cho biểu đồ nến 1m, đồng thời tối ưu hóa luồng gọi AI và bảo vệ bộ nhớ đệm:

### 1.1. Phân Bổ Nhà Cung Cấp Đối Xứng 1-to-1 (Fixed Provider Sharding)
| Phân Nhóm | Danh Mục Mã | Data Provider Cố Định | Gemini AI Shard | Đặc Tả Kỹ Thuật |
|---|---|---|---|---|
| **Crypto & Vàng** (3 mã) | `BTCUSDT`, `ETHUSDT`, `XAUUSD` | **Binance** | **Shard 1** (`GEMINI_SHARD_1`) | REST Klines + WebSocket tick real-time |
| **Cổ Phiếu Mỹ Nhóm A** (4 mã) | `AAPL`, `MSFT`, `NVDA`, `GOOGL` | **Alpaca** | **Shard 2** (`GEMINI_SHARD_2`) | IEX feed, `sort=desc`, start 5d/90d lấy 30 nến mới nhất |
| **Cổ Phiếu Mỹ Nhóm B** (4 mã) | `TSLA`, `AMZN`, `META`, `JPM` | **Twelve Data** | **Shard 3** (`GEMINI_SHARD_3`) | `/time_series` intraday 1m & daily |

### 1.2. Các Nguyên Tắc Bất Biến
- **Zero Fake Data:** Tuyệt đối không sinh dữ liệu nến, giá giả lập hay tin tức giả. Khi provider lỗi $\to$ HTTP 503 hoặc cache cũ kèm `stale=true`.
- **Cấm Fallback Chéo:** Mỗi Shard AI chạy độc lập với API key riêng. Khi Shard 1, 2 hoặc 3 lỗi/hết quota, hệ thống fail-closed ngay bằng `FORECAST_UNAVAILABLE`, không gọi chéo.
- **Bảo Vệ Polling Retry 24h:** Khuyến nghị AI được lưu `geminiAttemptTimestampMap`. Client polling 60s liên tục chỉ gọi Gemini duy nhất 1 lần trong 24h.
- **Bảo Toàn `aiShard`:** Migration V10 bổ sung cột `ai_shard` vào bảng `market_forecasts`, giúp lưu và phục hồi chính xác thông tin Shard AI xuyên suốt DB cache.

---

## 2. Kết Quả Kiểm Thử (100% PASS)
- **Backend Targeted Tests:** `FixedProviderShardingTest` (17/17 PASS).
- **Backend Full Suite:** 234/234 PASS (`mvn test`).
- **Android Targeted Tests:** `FixedProviderShardingAppUnitTest` (PASS).
- **Git Diff:** Sạch hoàn toàn, không có lỗi whitespace hay conflict.

---

## 3. Nhật Ký Vận Hành Production (Railway)
- Deployment ID: `ed355d65-8ea2-42bd-9ba0-8d57f575656f` đạt trạng thái `SUCCESS`.
- Migration V10 áp dụng thành công: `Migrating schema "public" to version "10 - add ai shard to market forecasts"`.
- Smoke test các nhóm mã:
  - `BTCUSDT` / `XAUUSD`: Dữ liệu live từ Binance, `stale: false`.
  - `AAPL` / `GOOGL`: Dữ liệu live từ Alpaca, nến 1m 30 bars mới nhất, `marketDataProvider: ALPACA`, `aiShard: GEMINI_SHARD_2`.
  - `TSLA` / `JPM`: Dữ liệu live từ Twelve Data, nến 1m 30 bars mới nhất, `marketDataProvider: TWELVE_DATA`, `aiShard: GEMINI_SHARD_3`.
  - `Forecast`: Phân tích thật từ Gemini Shard 2, lưu CSDL thành công và phục hồi chính xác từ cache với `fromCache: true` và `aiShard: GEMINI_SHARD_2`.
