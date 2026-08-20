# 📊 DỰ ÁN FNMF BACKEND - TÀI LIỆU BÀN GIAO & TRẠNG THÁI TOÀN DIỆN (PROJECT STATUS & HANDOVER)

> **Dành cho Lập trình viên và AI Agent kế thừa:**  
> File này chứa toàn bộ thông tin kiến trúc, cấu hình môi trường, tài khoản kết nối, trạng thái các module đã hoàn thành và hướng dẫn tiếp tục dự án.  
> **Quy tắc bắt buộc:** Mọi thay đổi hoặc cập nhật code trong tương lai **PHẢI** được cập nhật lại vào file này.

---

## 1. THÔNG TIN THÀNH VIÊN & DỰ ÁN
* **Tên đồ án:** FNMF - Financial News & Market Forecasting (Ứng dụng Tin tức Tài chính, Phân tích Cảm xúc AI & Giao dịch Giả lập).
* **Thành viên phụ trách Backend/Data:** Đặng Đức Khôi (Member #3).
* **Trưởng nhóm / PO / AI Prompt Engineer:** Nguyễn Hữu Mạnh (Member #1).
* **Thành viên Android Mobile App:** Nguyễn Quang Hùng (Member #2).
* **GitHub Repository:** [https://github.com/megatron69-26/fnmf-backend](https://github.com/megatron69-26/fnmf-backend)
* **Hai thư mục dự án trên máy tính:**
  1. `C:\Users\khoid\OneDrive\Desktop\llm-gateway2` *(Thư mục Git gốc)*
  2. `C:\Users\khoid\OneDrive\Desktop\cloud engineer\llm-gateway2` *(Thư mục IntelliJ IDEA đang mở)*
  *(Lưu ý: Luôn đồng bộ mã nguồn giữa 2 thư mục này).*

---

## 2. THÔNG SỐ MÔI TRƯỜNG & CẤU HÌNH HỆ THỐNG
* **Ngôn ngữ & Framework:** Java 17 (Adoptium OpenJDK), Spring Boot 3.3.5, Maven.
* **Cổng chạy Server:** `http://localhost:8082`
* **Giao diện Tài liệu Swagger UI:** `http://localhost:8082/swagger-ui/index.html`
* **Cơ sở dữ liệu:** Oracle Database 21c Express Edition (`localhost:1521/orcl`).
  * **Username:** `khoi2`
  * **Password:** `khoi2`
  * **7 Bảng CSDL đã tạo:** `USERS`, `WALLETS`, `HOLDINGS`, `TRANSACTIONS`, `WATCHLISTS`, `NEWS_AI_CACHE`, `MARKET_FORECASTS`.
* **API Keys & Dịch vụ bên ngoài:**
  * **Alpha Vantage API Key (Market Data & Real News):** `ZRA0HCT8FR32ID39` (URL: `https://www.alphavantage.co/query`)
  * **Google Gemini AI:** Model `gemini-2.0-flash` (Endpoint: `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`).

---

## 3. TIẾN ĐỘ & TRẠNG THÁI CÁC MODULE (100% HOÀN THÀNH TOÀN BỘ 4 TUẦN)

### ✅ TUẦN 1: HỆ THỐNG XÁC THỰC, PHÂN QUYỀN & VÍ TIỀN ẢO ($10,000)
1. **`POST /api/auth/register`**: Đăng ký tài khoản, mã hóa mật khẩu 1 chiều bằng `BCryptPasswordEncoder` (Salt 10 vòng), tự động khởi tạo Ví ảo $10,000 vốn ban đầu trong bảng `WALLETS`.
2. **`POST /api/auth/login`**: Đăng nhập, kiểm tra mật khẩu băm, sinh chuỗi `JWT Token` (HMAC-SHA256) có thời hạn 24 giờ.
3. **`GET /api/auth/me`**: Lấy thông tin cá nhân và số dư ví bằng Bearer Token.

---

### ✅ TUẦN 2: DỮ LIỆU THỊ TRƯỜNG, AI PHÂN TÍCH TIN TỨC, WATCHLIST & PAPER TRADING

#### 📈 Module 1: Dữ liệu Thị trường & Nến Nhật (Market Data & Candlesticks)
* **`GET /api/market/prices`**: Lấy giá thời gian thực của Bitcoin (`BTCUSDT`), Ethereum (`ETHUSDT`), Vàng (`XAUUSD`), Dầu thô (`USOIL`) từ Alpha Vantage.
* **`GET /api/market/price/{symbol}`**: Lấy giá chi tiết của 1 mã tài sản.
* **`GET /api/market/candles?symbol=BTCUSDT`**: Lấy chuỗi 30 cây nến OHLCV (Open, High, Low, Close, Volume) phục vụ Android vẽ Candlestick Chart.
* **🛡️ Cơ chế bảo vệ:** In-Memory Cache (TTL 30s) chống tràn Rate Limit 5 calls/phút của Alpha Vantage + `generateFallbackCandles()` sinh nến toán học mô phỏng khi mất mạng.

#### 🤖 Module 2: Pipeline Tin tức Thật & AI Phân tích Cảm xúc (AI News Sentiment & Oracle Cache)
* **`GET /api/news/feed?limit=5`**: **Pipeline tự động 100%**:
  1. Tự động lấy bài báo tài chính THẬT từ Alpha Vantage (`NEWS_SENTIMENT`).
  2. Đưa nội dung thật qua Google Gemini AI với Fixed System Prompt chuyên gia tài chính.
  3. Tự động gán nhãn `BULLISH` / `BEARISH` / `NEUTRAL`, độ tin cậy `%`, tóm tắt 3 ý và lý do.
  4. Tự động lưu bài báo và kết quả AI vào bảng `NEWS_AI_CACHE` trong Oracle DB.
  5. Trả về bài báo hoàn chỉnh (ảnh bìa, nguồn báo, phân tích AI) cho Android hiển thị.
* **`POST /api/news/analyze`**: Phân tích bài báo tùy chỉnh.
* **`GET /api/news/cache`**: Xem toàn bộ các bài báo đã được lưu trong CSDL Oracle.
* **🛡️ Cơ chế bảo vệ:** **2-Layer Caching** (Oracle DB cache trả về trong < 5ms) + **Circuit Breaker Financial Heuristic Engine** (tự động phân tích theo từ khóa vĩ mô nếu Gemini bị lỗi/mất mạng).

#### ⭐ Module 3: Quản lý Danh mục Theo dõi (Watchlist CRUD)
* **`GET /api/watchlist`**: Lấy danh mục cá nhân của User (Bearer Token), tự động ghép giá thị trường và biến động 24h từ Alpha Vantage (0 Token AI).
* **`GET /api/watchlist/ai-insights`** *(TÍNH NĂNG CÁ NHÂN HÓA)*:
  Tự động quét toàn bộ các mã trong Watchlist của User $\rightarrow$ Gọi Gemini AI để lấy Dự báo chiến lược + Tin tức tóm tắt riêng cho danh mục yêu thích của User đó.
* **`POST /api/watchlist`**: Thêm mã tài sản mới (Body: `{ "symbol": "ETHUSDT" }`), có kiểm tra chống trùng lặp.
* **`DELETE /api/watchlist/{symbol}`**: Xóa mã khỏi danh mục theo dõi.

#### 💼 Module 4: Giao dịch Giả lập & Quản lý Danh mục (Paper Trading & Realtime PnL)
* **`POST /api/trade/order`**: Đặt lệnh MUA (`BUY`) hoặc BÁN (`SELL`) theo giá thị trường thời gian thực của Alpha Vantage.
  * Tự động kiểm tra số dư ví khả dụng (chống âm tiền).
  * Tự động tính Giá mua trung bình (DCA): `newAvgPrice = (oldCost + newCost) / (oldQty + newQty)`.
  * Đảm bảo tính toàn vẹn **ACID (`@Transactional`)** trong Oracle DB.
* **`GET /api/trade/portfolio`**: Lấy tổng quan tài sản ròng (Net Worth = Tiền mặt + Giá trị các mã đang nắm giữ) và Lời/Lỗ (PnL) thời gian thực.
* **`GET /api/trade/history`**: Lấy toàn bộ lịch sử các lệnh Mua/Bán đã khớp trong bảng `TRANSACTIONS`.

---

### ✅ TUẦN 3: TÍNH NĂNG NÂNG CAO & DỰ BÁO THỊ TRƯỜNG AI

#### 🔮 Module 1: Dự báo Xu hướng & Tín hiệu Giao dịch AI (AI Market Forecasting)
* **`GET /api/forecast/{symbol}?timeframe=24H_7D`**: Lấy phân tích dự báo xu hướng đa chiều cho mã tài sản.
* **`POST /api/forecast/analyze`**: Tạo hoặc làm mới bản dự báo thị trường AI (Body: `{ "symbol": "XAUUSD" }`).
* **`GET /api/forecast/history/{symbol}`**: Xem lịch sử các bản dự báo AI trong quá khứ của mã tài sản.
* **`GET /api/forecast/latest`**: Lấy 10 bản dự báo AI mới nhất trong CSDL Oracle.
* **🧠 Cơ chế hoạt động:** Kết hợp **30 nến kỹ thuật (Module 1)** + **Tin tức tâm lý vĩ mô gần nhất (Module 2)** gửi sang Gemini AI để tính toán Vùng Hỗ trợ (Support), Kháng cự (Resistance), Xu hướng và Khuyến nghị (`STRONG_BUY`, `BUY`, `HOLD`, `SELL`, `STRONG_SELL`).
* **🛡️ Cơ chế bảo vệ:** Cache CSDL Oracle 15 phút (bảng `MARKET_FORECASTS`) + **Heuristic Quantitative Forecaster** dự phòng khi mất kết nối AI.

---

### ✅ TUẦN 4: ĐÓNG GÓI, SWAGGER OPENAPI & HOÀN THIỆN ĐỒ ÁN
1. **Swagger UI / OpenAPI 3.0 Integration:**
   * Tích hợp `springdoc-openapi-starter-webmvc-ui` (v2.6.0).
   * Tự động sinh tài liệu tại `http://localhost:8082/swagger-ui/index.html` với tính năng **Authorize JWT Bearer Token** trực tiếp trên web.
2. **Postman Collection Export:**
   * File [`fnmf_backend_postman_collection.json`](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/fnmf_backend_postman_collection.json) chứa toàn bộ 18 API có sẵn dữ liệu mẫu.
3. **CORS & Global Exception Handling:**
   * Cấu hình CORS mở toàn bộ trong `AppConfig.java` cho Android và Web.
   * `GlobalExceptionHandler.java` bắt và chuẩn hóa mọi mã lỗi 400, 401, 500 thành JSON thân thiện.

---

## 4. BỘ CÂU HỎI BẢO VỆ ĐỒ ÁN (DEFENSE Q&A CHEATSHEET)

| Câu hỏi của Giảng viên | Cách trả lời chuẩn | File & Vị trí Code |
| :--- | :--- | :--- |
| **1. Mất mạng Gemini thì sao?** | Có cơ chế Fallback Circuit Breaker tự động chuyển sang Financial Heuristic Engine phân tích từ khóa, không bao giờ Crash app. | [`AiNewsService.java` (L280-L325)](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/service/AiNewsService.java#L280-L325) |
| **2. Tối ưu chi phí & độ trễ AI?** | 2-Layer Caching với Oracle DB `NEWS_AI_CACHE`. Bài cũ nạp trong < 5ms với `fromCache: true`, chỉ gọi AI khi có tin mới. | [`AiNewsService.java` (L60-L90)](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/service/AiNewsService.java#L60-L90) |
| **3. Alpha Vantage giới hạn 5 req/phút?** | In-Memory Cache (TTL 30s) + Thuật toán sinh nến mô phỏng `generateFallbackCandles()`. | [`MarketDataService.java` (L40-L65)](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/service/MarketDataService.java#L40-L65) |
| **4. Tính toàn vẹn khớp lệnh ví ảo?** | `@Transactional` của Spring JPA + Kiểm tra số dư nghiêm ngặt, tự động Rollback nếu 1 bước lỗi. | [`TradeService.java` (L45-L95)](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/service/TradeService.java#L45-L95) |
| **5. Công thức tính DCA & PnL?** | `newAvgPrice = (oldCost + newCost) / newQty`. PnL = `(CurrentPrice - AvgPrice) * Qty`. | [`TradeService.java` (L75-L88)](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/service/TradeService.java#L75-L88) |
| **6. Mô hình Dự báo AI hoạt động thế nào?** | Kết hợp đa tầng: Nến 30 ngày (Kỹ thuật) + Tin tức vĩ mô (Tâm lý) qua Gemini AI để xác định Hỗ trợ/Kháng cự và Khuyến nghị mua/bán. | [`ForecastService.java` (L45-L90)](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/service/ForecastService.java#L45-L90) |
| **7. Tài liệu API được quản lý ra sao?** | Tự động sinh bởi OpenAPI 3.0 / Swagger UI tại `/swagger-ui/index.html` và bộ file Postman Collection JSON. | [`OpenApiConfig.java`](file:///c:/Users/khoid/OneDrive/Desktop/llm-gateway2/src/main/java/com/llmgateway/config/OpenApiConfig.java) |

---

## 5. HƯỚNG DẪN DÀNH CHO AGENT TIẾP THEO (AI AGENT INSTRUCTIONS)
1. Khi tiếp tục dự án, hãy đọc kỹ file này trước tiên để nắm toàn bộ bối cảnh.
2. Kiểm tra Oracle DB (`localhost:1521/orcl`) và port `8082` trước khi chạy lệnh.
3. Khi chỉnh sửa mã nguồn, luôn đồng bộ giữa `C:\Users\khoid\OneDrive\Desktop\llm-gateway2` và `C:\Users\khoid\OneDrive\Desktop\cloud engineer\llm-gateway2`.
4. Sau khi hoàn thành tính năng mới, hãy cập nhật lại file `PROJECT_STATUS.md` này.
