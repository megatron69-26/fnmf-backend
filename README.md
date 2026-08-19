# FNMF Backend & AI Gateway
**Financial News & Market Forecasting (FNMF)**  
*Backend REST API, Oracle Database & AI Processing Layer*

- **Người thực hiện:** Đặng Đức Khôi (Backend / Data Developer)
- **Công nghệ cốt lõi:** Java 17, Spring Boot 3.3.5, Spring Data JPA, Oracle Database 21c, Spring Security (BCrypt), JJWT, Alpha Vantage API, Gemini AI.

---

## 📌 1. Tổng quan & Các tính năng hoàn thành (Tuần 1 & Tuần 2)

Dự án Backend đóng vai trò làm trung tâm xử lý nghiệp vụ, quản lý dữ liệu và cầu nối AI cho toàn bộ ứng dụng di động FNMF:

1. **Xác thực & Bảo mật (Auth Module):**
   - Đăng ký, Đăng nhập với mật khẩu mã hóa một chiều **BCrypt**.
   - Cấp vé thông hành định danh **JWT Bearer Token** (thời hạn 24h).
   - Tự động khởi tạo và cấp ví vốn ảo mặc định **$10,000.00** cho mỗi người dùng mới.

2. **Dữ liệu Thị trường Thời gian thực (Market Data & Candlestick):**
   - Tích hợp **Alpha Vantage API** lấy giá thời gian thực của Bitcoin, Ethereum, Vàng thế giới (XAUUSD), Dầu thô WTI (USOIL).
   - Cung cấp chuỗi nến lịch sử **OHLCV** (Open, High, Low, Close, Volume) để Android vẽ Candlestick Chart.
   - Cung cấp dòng tin tức tài chính kinh tế thế giới (`NEWS_SENTIMENT`).

3. **Phân tích Tin tức Tài chính bằng AI (Gemini AI Sentiment & Cache):**
   - Tự động tóm tắt 3-5 gạch đầu dòng trọng tâm của bài báo kinh tế.
   - Gán nhãn xu hướng: `BULLISH` (Tăng/Cơ hội), `BEARISH` (Giảm/Rủi ro), `NEUTRAL` (Trung lập) kèm % độ tin cậy và lý do.
   - **Cơ chế Caching Oracle DB:** Lưu bài báo đã phân tích vào bảng `NEWS_AI_CACHE`, tái sử dụng kết quả trong < 5ms (chống tốn quota AI).

4. **Quản lý Danh mục Theo dõi (Watchlist CRUD):**
   - Thêm, xem, xóa các mã tài sản yêu thích vào bảng `WATCHLISTS`, tự động đính kèm giá thị trường và biến động 24h.

5. **Giao dịch Giả lập (Paper Trading & Quản lý Danh mục):**
   - Đặt lệnh **MUA (BUY)** và **BÁN (SELL)** với giá thị trường thời gian thực.
   - Kiểm tra số dư ví ảo $\rightarrow$ Trừ/cộng tiền trong bảng `WALLETS`.
   - Tính toán giá vốn trung bình và khối lượng trong bảng `HOLDINGS`.
   - Ghi nhận lịch sử giao dịch vào bảng `TRANSACTIONS`.
   - Tính toán tổng tài sản ròng (**Net Worth**) và tỷ lệ **Lời/Lỗ (PnL)** thời gian thực.

---

## 🗄️ 2. Cấu trúc Cơ sở dữ liệu (Oracle Database)

Hệ thống CSDL chạy trên **Oracle Database** (Schema: `KHOI2` / Connection: `khoi_mobile`) bao gồm 6 bảng:
1. `USERS`: Lưu trữ tài khoản và mật khẩu đã băm (`password_hash`).
2. `WALLETS`: Lưu trữ ví vốn ảo, số dư khả dụng và vốn khởi tạo ($10,000.00).
3. `HOLDINGS`: Danh mục tài sản ảo đang nắm giữ (Vàng XAUUSD, Dầu USOIL, Bitcoin BTCUSDT...).
4. `TRANSACTIONS`: Lịch sử các lệnh Mua/Bán khớp lệnh thời gian thực.
5. `WATCHLISTS`: Danh mục theo dõi yêu thích của từng người dùng (CRUD).
6. `NEWS_AI_CACHE`: Bộ nhớ đệm lưu trữ bài báo và kết quả tóm tắt / phân tích tâm lý từ Gemini AI.

---

## 📖 3. Tài liệu API (API Reference)

### 🔐 A. NHÓM API AUTH (XÁC THỰC)
* `POST /api/auth/register` : Đăng ký tài khoản mới $\rightarrow$ Tự động cấp ví ảo $10,000.00.
* `POST /api/auth/login` : Đăng nhập $\rightarrow$ Nhận JWT Bearer Token.
* `GET /api/auth/me` : Lấy profile user và số dư ví (Header `Authorization: Bearer <token>`).

---

### 📈 B. NHÓM API MARKET DATA (DỮ LIỆU THỊ TRƯỜNG)
* `GET /api/market/prices` : Lấy giá thời gian thực của tất cả tài sản (BTC, ETH, Vàng, Dầu).
* `GET /api/market/price/{symbol}` : Lấy giá của 1 mã cụ thể (ví dụ: `BTCUSDT`).
* `GET /api/market/candles?symbol=BTCUSDT&interval=daily` : Lấy 30 cây nến OHLCV để vẽ biểu đồ.
* `GET /api/market/news?limit=10` : Lấy dòng tin tức kinh tế mới nhất.

---

### 🧠 C. NHÓM API AI NEWS & SENTIMENT
* `POST /api/news/analyze` : Gửi bài báo để AI tóm tắt 3 ý + gán nhãn Bullish/Bearish.
* `GET /api/news/cache` : Xem danh sách các bài báo đã được AI phân tích trong CSDL.

**Body mẫu `POST /api/news/analyze`:**
```json
{
  "title": "FED quyết định hạ lãi suất 0.5%",
  "content": "Cục Dự trữ Liên bang Mỹ vừa hạ lãi suất...",
  "symbol": "XAUUSD"
}
```

---

### 📋 D. NHÓM API WATCHLIST (DANH MỤC THEO DÕI)
*(Yêu cầu Header `Authorization: Bearer <token>`)*
* `GET /api/watchlist` : Lấy danh sách theo dõi của User.
* `POST /api/watchlist` : Thêm mã mới vào danh mục (`{ "symbol": "BTCUSDT" }`).
* `DELETE /api/watchlist/{symbol}` : Xóa mã khỏi danh mục.

---

### 💰 E. NHÓM API PAPER TRADING (GIAO DỊCH GIẢ LẬP)
*(Yêu cầu Header `Authorization: Bearer <token>`)*
* `POST /api/trade/order` : Đặt lệnh Mua/Bán giả lập.
* `GET /api/trade/portfolio` : Xem tổng quan tài sản ròng, tiền mặt, danh mục đang giữ và PnL.
* `GET /api/trade/history` : Xem toàn bộ lịch sử giao dịch Mua/Bán.

**Body mẫu `POST /api/trade/order`:**
```json
{
  "symbol": "BTCUSDT",
  "type": "BUY",
  "quantity": 0.05
}
```
hoặc
```json
{
  "symbol": "BTCUSDT",
  "type": "SELL",
  "quantity": 0.02
}
```
