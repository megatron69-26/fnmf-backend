# 🚀 FNMF Backend & AI Gateway
**Financial News & Market Forecasting (FNMF)**  
*Backend REST API, Oracle Database & AI Processing Layer*

- **Người thực hiện:** Đặng Đức Khôi (Backend / Data Developer)
- **Công nghệ cốt lõi:** Java 17, Spring Boot 3.3.5, Spring Data JPA, Oracle Database 21c, Spring Security (BCrypt), JJWT, Alpha Vantage API, Gemini AI.
- **Base URL:** `http://localhost:8082`

---

## 📌 1. Bảng ánh xạ Module, Endpoint & File mã nguồn (Source Code Mapping)

| Module | Chức năng | Phương thức & Đường dẫn API | File Controller & Service |
| :--- | :--- | :--- | :--- |
| **Auth** | Đăng ký & Cấp ví $10,000 | `POST /api/auth/register` | `AuthController.java` / `AuthService.java` |
| **Auth** | Đăng nhập & Nhận JWT | `POST /api/auth/login` | `AuthController.java` / `AuthService.java` |
| **Auth** | Xem thông tin User & Số dư | `GET /api/auth/me` | `AuthController.java` / `AuthService.java` |
| **Market** | Giá thời gian thực (BTC, ETH, Vàng, Dầu) | `GET /api/market/prices` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Giá 1 mã cụ thể | `GET /api/market/price/{symbol}` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Chuỗi nến 30 ngày vẽ Chart | `GET /api/market/candles?symbol=BTCUSDT` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Dòng tin tức tài chính gốc | `GET /api/market/news?limit=10` | `MarketController.java` / `MarketDataService.java` |
| **AI News** | **Pipeline AI News tự động (Alpha Vantage + Gemini + Oracle)** | `GET /api/news/feed?limit=5` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Phân tích bài báo bất kỳ | `POST /api/news/analyze` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Xem Cache bài báo trong CSDL Oracle | `GET /api/news/cache` | `NewsAiController.java` / `AiNewsService.java` |
| **Watchlist** | Lấy danh mục theo dõi của User | `GET /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | Thêm mã vào danh mục theo dõi | `POST /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | Xóa mã khỏi danh mục theo dõi | `DELETE /api/watchlist/{symbol}` | `WatchlistController.java` / `WatchlistService.java` |
| **Trade** | Đặt lệnh Mua/Bán giả lập (Paper Trading) | `POST /api/trade/order` | `TradeController.java` / `TradeService.java` |
| **Trade** | Tổng quan tài sản & PnL thời gian thực | `GET /api/trade/portfolio` | `TradeController.java` / `TradeService.java` |
| **Trade** | Lịch sử các lệnh đã khớp | `GET /api/trade/history` | `TradeController.java` / `TradeService.java` |
| **Forecast** | **Dự báo xu hướng & Tín hiệu AI cho 1 mã** | `GET /api/forecast/{symbol}` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Tạo / Làm mới bản dự báo AI | `POST /api/forecast/analyze` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Lịch sử các bản dự báo AI của mã | `GET /api/forecast/history/{symbol}` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Danh sách các bản dự báo AI mới nhất | `GET /api/forecast/latest` | `ForecastController.java` / `ForecastService.java` |

---

## 🗄️ 2. Cấu trúc Cơ sở dữ liệu (Oracle Database 21c)

Hệ thống CSDL chạy trên **Oracle Database 21c** (`localhost:1521/orcl`, User `khoi2`) bao gồm 7 bảng:
1. `USERS`: Lưu trữ tài khoản và mật khẩu đã băm (`password_hash`).
2. `WALLETS`: Lưu trữ ví vốn ảo, số dư khả dụng và vốn khởi tạo ($10,000.00).
3. `HOLDINGS`: Danh mục tài sản ảo đang nắm giữ (Vàng XAUUSD, Dầu USOIL, Bitcoin BTCUSDT...).
4. `TRANSACTIONS`: Lịch sử các lệnh Mua/Bán khớp lệnh thời gian thực.
5. `WATCHLISTS`: Danh mục theo dõi yêu thích của từng người dùng (CRUD).
6. `NEWS_AI_CACHE`: Bộ nhớ đệm lưu trữ bài báo và kết quả tóm tắt / phân tích tâm lý từ Gemini AI.
7. `MARKET_FORECASTS`: Bảng lưu trữ các bản dự báo xu hướng, vùng hỗ trợ/kháng cự và khuyến nghị đầu tư từ AI (Cache 15 phút).

---

## 📖 3. Chi tiết các Endpoint & Dữ liệu Mẫu (API Specifications)

### 🔮 F. MODULE DỰ BÁO THỊ TRƯỜNG & TÍN HIỆU ĐẦU TƯ AI (FORECASTING)
* **`GET /api/forecast/{symbol}?timeframe=24H_7D`** (Ví dụ: `GET /api/forecast/BTCUSDT`):
  ```json
  {
    "symbol": "BTCUSDT",
    "assetName": "Bitcoin",
    "currentPrice": 69653.74,
    "trendPrediction": "BULLISH_UPTREND",
    "timeframe": "24H_7D",
    "supportLevel": 67215.86,
    "resistanceLevel": 72788.16,
    "recommendation": "STRONG_BUY",
    "confidenceScore": 92,
    "keyDrivers": [
      "Giá Bitcoin ($69653.74) đang vận động trên vùng hỗ trợ kỹ thuật $67215.86.",
      "Tâm lý tin tức vĩ mô ghi nhận 7 tín hiệu tích cực và 0 tín hiệu rủi ro.",
      "Khuyến nghị chiến lược: Phù hợp giải ngân tỷ trọng theo xu hướng BULLISH_UPTREND."
    ],
    "technicalOutlook": "Đồ thị nến duy trì dao động tích lũy quanh ngưỡng trung bình động 20 ngày.",
    "fundamentalOutlook": "Bối cảnh vĩ mô quốc tế tiếp tục chi phối tâm lý dòng tiền ngắn hạn.",
    "fromCache": true,
    "createdAt": "2026-08-20T14:26:47"
  }
  ```
* **`POST /api/forecast/analyze`**:
  ```json
  // Request Body:
  {
    "symbol": "XAUUSD",
    "timeframe": "24H_7D"
  }
  ```

---

## 🛡️ 4. Cơ chế Phòng vệ An toàn (Circuit Breakers & Fallback)
1. **In-Memory Cache (TTL 30s):** Bảo vệ giới hạn Rate Limit 5 calls/phút của Alpha Vantage.
2. **Fallback Candlestick Generator:** Thuật toán sinh nến mô phỏng (Sin Wave + Random Walk) giúp Android luôn vẽ được biểu đồ khi mất kết nối Alpha Vantage.
3. **2-Layer AI News Caching:** Cache bài báo trong CSDL Oracle, giảm độ trễ phản hồi xuống `< 5ms` và tiết kiệm chi phí AI.
4. **Financial Heuristic Engine:** Tự động phân tích tâm lý thị trường khi mất kết nối tới Google Gemini.
5. **Heuristic Quantitative Forecaster:** Tự động tính toán vùng Hỗ trợ/Kháng cự và Xu hướng khi mất kết nối Gemini, đảm bảo Server luôn đạt 100% Uptime.
