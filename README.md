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

---

## 🗄️ 2. Cấu trúc Cơ sở dữ liệu (Oracle Database 21c)

Hệ thống CSDL chạy trên **Oracle Database 21c** (`localhost:1521/orcl`, User `khoi2`) bao gồm 6 bảng:
1. `USERS`: Lưu trữ tài khoản và mật khẩu đã băm (`password_hash`).
2. `WALLETS`: Lưu trữ ví vốn ảo, số dư khả dụng và vốn khởi tạo ($10,000.00).
3. `HOLDINGS`: Danh mục tài sản ảo đang nắm giữ (Vàng XAUUSD, Dầu USOIL, Bitcoin BTCUSDT...).
4. `TRANSACTIONS`: Lịch sử các lệnh Mua/Bán khớp lệnh thời gian thực.
5. `WATCHLISTS`: Danh mục theo dõi yêu thích của từng người dùng (CRUD).
6. `NEWS_AI_CACHE`: Bộ nhớ đệm lưu trữ bài báo và kết quả tóm tắt / phân tích tâm lý từ Gemini AI.

---

## 📖 3. Chi tiết các Endpoint & Dữ liệu Mẫu (API Specifications)

### 🔐 A. MODULE XÁC THỰC (AUTH)
* **`POST /api/auth/register`**
  ```json
  // Request Body:
  {
    "email": "khoi.pro@fnmf.com",
    "password": "mypassword123",
    "fullName": "Dang Duc Khoi"
  }
  ```
* **`POST /api/auth/login`**
  ```json
  // Request Body:
  {
    "email": "khoi.pro@fnmf.com",
    "password": "mypassword123"
  }
  ```
* **`GET /api/auth/me`** (Header: `Authorization: Bearer <token>`)

---

### 📈 B. MODULE DỮ LIỆU THỊ TRƯỜNG (MARKET DATA)
* **`GET /api/market/prices`**: Trả về danh sách giá thời gian thực của BTC, ETH, XAUUSD, USOIL.
* **`GET /api/market/candles?symbol=BTCUSDT&interval=daily`**: Trả về mảng 30 nến OHLCV:
  ```json
  [
    {
      "time": "2026-08-19",
      "open": 64681.33,
      "high": 64705.56,
      "low": 64200.10,
      "close": 64450.00,
      "volume": 2840.5
    }
  ]
  ```

---

### 🧠 C. MODULE AI NEWS & SENTIMENT
* **`GET /api/news/feed?limit=5`** *(Pipeline tự động 100%)*:
  Tự động cào tin thật từ Alpha Vantage $\rightarrow$ Phân tích qua Gemini AI $\rightarrow$ Lưu Oracle DB `NEWS_AI_CACHE` $\rightarrow$ Trả về cho App.
* **`POST /api/news/analyze`**:
  ```json
  // Request Body:
  {
    "title": "FED tuyên bố hạ lãi suất 0.5%",
    "content": "Cục Dự trữ Liên bang Mỹ vừa quyết định giảm lãi suất cơ bản...",
    "symbol": "XAUUSD"
  }
  ```
* **`GET /api/news/cache`**: Xem toàn bộ tin tức đã được AI phân tích trong CSDL Oracle.

---

### 📋 D. MODULE WATCHLIST (DANH MỤC THEO DÕI)
*(Yêu cầu Header: `Authorization: Bearer <token>`)*
* **`GET /api/watchlist`**: Lấy danh mục theo dõi kèm giá nhảy thời gian thực.
* **`POST /api/watchlist`**:
  ```json
  // Request Body:
  {
    "symbol": "ETHUSDT"
  }
  ```
* **`DELETE /api/watchlist/{symbol}`**: Xóa mã khỏi danh mục theo dõi.

---

### 💰 E. MODULE PAPER TRADING (GIAO DỊCH GIẢ LẬP)
*(Yêu cầu Header: `Authorization: Bearer <token>`)*
* **`POST /api/trade/order`**:
  ```json
  // Lệnh MUA:
  {
    "symbol": "BTCUSDT",
    "type": "BUY",
    "quantity": 0.05
  }
  // Lệnh BÁN:
  {
    "symbol": "BTCUSDT",
    "type": "SELL",
    "quantity": 0.02
  }
  ```
* **`GET /api/trade/portfolio`**: Xem tổng tài sản ròng (Net Worth), tiền mặt khả dụng và Lời/Lỗ (PnL) chi tiết.
* **`GET /api/trade/history`**: Xem toàn bộ lịch sử các lệnh Mua/Bán đã khớp.

---

## 🛡️ 4. Cơ chế Phòng vệ An toàn (Circuit Breakers & Fallback)
1. **In-Memory Cache (TTL 30s):** Bảo vệ giới hạn Rate Limit 5 calls/phút của Alpha Vantage.
2. **Fallback Candlestick Generator:** Thuật toán sinh nến mô phỏng (Sin Wave + Random Walk) giúp Android luôn vẽ được biểu đồ khi mất kết nối Alpha Vantage.
3. **2-Layer AI News Caching:** Cache bài báo trong CSDL Oracle, giảm độ trễ phản hồi xuống `< 5ms` và tiết kiệm chi phí AI.
4. **Financial Heuristic Engine:** Tự động phân tích tâm lý thị trường khi mất kết nối tới Google Gemini, đảm bảo App Android không bao giờ bị Crash.
