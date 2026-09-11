# FNMF Backend & AI Gateway

Tai lieu doc ma nguon theo luong nghiep vu: [FNMF Codebase Walkthrough](docs/codebase-walkthrough/README.md)

**Financial News & Market Forecasting (FNMF)**  
*Backend REST API, High-Integrity Financial Data Pipeline & AI Processing Layer*

- **Nguoi thuc hien:** Dang Duc Khoi (Backend & Data Developer)
- **Cong nghe cot loi:** Java 17, Spring Boot 3.3.5, Spring Data JPA, H2 Database (Local Dev/Test voi che do MODE=PostgreSQL) / PostgreSQL (Railway Production), Flyway Migrations (V1 - V8), Spring Security (BCrypt), JJWT, Binance REST API (Klines) & Binance WebSocket, Alpha Vantage API (News Sentiment), Google Gemini AI (OpenAI-compatible protocol voi model gemini-3.6-flash), OpenAPI 3.0 / Swagger UI (Dev/Local only; bi ProductionSecurityFilter chan tren Production).
- **Base URL Local:** `http://localhost:8083`
- **Base URL Production (Railway):** `https://fnmf-backend-production.up.railway.app/`
- **Swagger UI (Local Dev):** `http://localhost:8083/swagger-ui.html`

---

## 1. Bang anh xa Module, Endpoint & File ma nguon

| Module | Chuc nang | Phuong thuc & Duong dan API | File Controller & Service |
| :--- | :--- | :--- | :--- |
| **Auth** | Dang ky tai khoan Email-only | `POST /api/auth/register` | `AuthController.java` / `AuthService.java` |
| **Auth** | Dang nhap tai khoan & Nhan JWT | `POST /api/auth/login` | `AuthController.java` / `AuthService.java` |
| **Auth** | Xem thong tin User & So du | `GET /api/auth/me` | `AuthController.java` / `AuthService.java` |
| **Market** | Gia thi truong thoi gian thuc (BTC, ETH, Vang) | `GET /api/market/prices` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Gia mot ma cu the (Authoritative check) | `GET /api/market/price/{symbol}` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Chuoi nen that tu Binance Klines | `GET /api/market/candles?symbol=BTCUSDT` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Tin tuc tai chinh goc tu Alpha Vantage | `GET /api/market/news?limit=10` | `MarketController.java` / `MarketDataService.java` |
| **AI News** | Pipeline AI News tu dong (Alpha Vantage + Gemini) | `GET /api/news/feed?limit=5` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Phan tich bai bao bat ky bang AI | `POST /api/news/analyze` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Xem Cache bai bao trong CSDL | `GET /api/news/cache` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Dong bo bai bao phuc vu Mobile & Room DB | `GET /api/news/sync` | `NewsAiController.java` / `AiNewsService.java` |
| **Watchlist** | Lay danh muc theo doi cua User | `GET /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | Ban tin AI chuyen sau cho cac ma Watchlist | `GET /api/watchlist/ai-insights` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | Them ma vao danh muc theo doi | `POST /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | Xoa ma khoi danh muc theo doi | `DELETE /api/watchlist/{symbol}` | `WatchlistController.java` / `WatchlistService.java` |
| **Trade** | Dat lenh Paper Trading (Pessimistic Lock & Idempotency) | `POST /api/trade/order` | `TradeController.java` / `TradeService.java` |
| **Trade** | Tong quan tai san & PnL thoi gian thuc | `GET /api/trade/portfolio` | `TradeController.java` / `TradeService.java` |
| **Trade** | Lich su cac lenh da khop | `GET /api/trade/history` | `TradeController.java` / `TradeService.java` |
| **Forecast** | Du bao xu huong & Tin hieu AI cho mot ma | `GET /api/forecast/{symbol}?timeframe=24H_7D` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Tao / Lam moi ban du bao AI | `POST /api/forecast/analyze` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Lich su cac ban du bao AI cua ma | `GET /api/forecast/history/{symbol}` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Danh sach cac ban du bao AI moi nhat | `GET /api/forecast/latest` | `ForecastController.java` / `ForecastService.java` |
| **Payment** | Tao yeu cau nap tien Sandbox | `POST /api/payments/deposits` | `PaymentController.java` / `PaymentService.java` |
| **Payment** | Tao yeu cau rut tien Sandbox | `POST /api/payments/withdrawals` | `PaymentController.java` / `PaymentService.java` |
| **Payment** | Lay danh sach giao dich nap rut | `GET /api/payments` | `PaymentController.java` / `PaymentService.java` |
| **Payment** | Chi tiet don hang nap rut | `GET /api/payments/{id}` | `PaymentController.java` / `PaymentService.java` |
| **Payment** | Huy don hang nap rut dang cho | `POST /api/payments/{id}/cancel` | `PaymentController.java` / `PaymentService.java` |
| **Payment UI** | Giao dien Hosted Checkout Sandbox | `GET /sandbox-bank/checkout/{token}` | `SandboxBankCheckoutController.java` |
| **Payment UI** | Xu ly thanh toan Sandbox tu trinh duyet | `POST /sandbox-bank/checkout/process` | `SandboxBankCheckoutController.java` |

---

## 2. Cau truc Co so du lieu & Quan ly Migration (Flyway)

He thong ho tro hai moi truong co so du lieu:
- **Local Development / Test:** H2 Database in-memory che do PostgreSQL (`jdbc:h2:mem:fnmf;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`).
- **Production (Railway):** PostgreSQL quan ly tap trung qua cac migration Flyway tu dong (V1 den V8):
  - `V1__init_schema.sql`: Khoi tao bang nguoi dung, vi von, danh muc nam giu, lich su giao dich, watchlist, cache tin tuc va du bao thi truong.
  - `V2__normalize_and_enforce_user_email.sql`: Chuan hoa mo hinh email-only va rang buoc duy nhat `LOWER(email)`.
  - `V3__holding_unique_constraint_and_client_order_id.sql`: Rang buoc duy nhat `(WALLET_ID, SYMBOL)` chong nhan doi tai san va cot `CLIENT_ORDER_ID` chong trung lap lenh dong thoi.
  - `V4__add_payment_orders_and_checkout_token.sql`: Khoi tao bang `PAYMENT_ORDERS` va co che checkout token cho Sandbox Banking.
  - `V5__add_wallet_ledger_audit.sql`: Khoi tao bang so cai bien dong so du bat bien `WALLET_LEDGER`.
  - `V6__add_payment_events_and_scale_guards.sql`: Bo sung bang luu vet su kien `PAYMENT_EVENTS` va rang buoc scale so tien.
  - `V7__harden_payment_flow_and_order_indexes.sql`: Thiet lap chi muc va kiem soat het han 15 phut cho giao dich payment.
  - `V8__add_forecast_source_and_metadata.sql`: Bo sung cot phan loai nguon du bao (`source`), metadata phan tich va cac chi so chat luong forecast.

Chi tiet 10 bang du lieu trong CSDL:
1. `USERS`: Luu tru tai khoan email-only, phan quyen (`USER` / `ADMIN`), mat khau bam BCrypt.
2. `WALLETS`: Luu tru vi von ao, so du kha dung va von khoi tao ($10,000.00). Khoa bi quan (`PESSIMISTIC_WRITE`) khi dat lenh va nap rut.
3. `HOLDINGS`: Danh muc tai san ao dang nam giu (`BTCUSDT`, `ETHUSDT`, `XAUUSD`). Khoa bi quan va rang buoc duy nhat `(WALLET_ID, SYMBOL)`.
4. `TRANSACTIONS`: Lich su cac lenh Mua/Ban khop lenh thoi gian thuc, luu `CLIENT_ORDER_ID` de bao dam Idempotency.
5. `WATCHLISTS`: Danh muc theo doi yeu thich cua tung nguoi dung (Cloud CRUD), phan tach doc lap theo `USER_ID`.
6. `NEWS_AI_CACHE`: Bo nho dem luu tru bai bao va ket qua tom tat (2-4 bullet) / phan tich tam ly tu Gemini AI.
7. `MARKET_FORECASTS`: Bang luu tru cac ban du bao xu huong, vung ho tro/khang cu va khuyen nghi tu AI (Cache 15 phut).
8. `PAYMENT_ORDERS`: Don hang nap/rut tien Sandbox, trang thai (`PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `CANCELLED`), token thanh toan co han 15 phut.
9. `WALLET_LEDGER`: So cai bien dong so du don (single-entry append-only ledger), luu moi giao dich lam thay doi so du vi.
10. `PAYMENT_EVENTS`: Nhat ky su kien thanh toan phuc vu truy vet kiem toan va dam bao tinh luy thua.

---

## 3. Co che Bao dam Toan ven Du lieu & Chinh sach Khong Su dung Du lieu Gia (Zero-Fake Policy)

1. **Nguon du lieu thi truong Authoritative:**
   - Gia va nen thoi gian thuc lay truc tiep tu Binance REST API (`/api/v3/ticker/24hr`, `/api/v3/klines`).
   - Android client mo ket noi Binance WebSocket de nhan tick gia live cap nhat bieu do.
2. **Chinh sach Symbol Hop le & Loai bo USOIL:**
   - Cac ma hop le: `BTCUSDT`, `ETHUSDT`, `XAUUSD` (tham chieu `PAXGUSDT`).
   - Ma `USOIL` va cac ma khong ho tro bi tu choi hoan toan voi HTTP 422 `UNSUPPORTED_SYMBOL`. Tuyet doi khong bao gio fallback ve BTC.
3. **Xoa bo hoan toan Mock va Random Data tren Production:**
   - Da loai bo triet de cac ham sinh nen toan hoc mo phong, `Math.random()` va gia hardcode tren production backend.
   - Khi mat ket noi nha cung cap va chua tung co cache that: Tra ve HTTP 503 `DATA_UNAVAILABLE`.
   - Neu co cache that cua chinh ma do: Tra ve cache that cuoi cung kem co `stale=true`.
4. **Idempotency & Khoa giao dich dong thoi (Pessimistic Locking):**
   - Dat lenh va nap rut tien su dung `@Lock(LockModeType.PESSIMISTIC_WRITE)` tren `Wallet` va `Holding` de ngan ngua race condition khi nhieu request gui dong thoi.
   - Ho tro `clientOrderId` (UUID): Tu dong phat hien va replay ket qua lenh cu neu nhan trung ma yeu cau, khong tru tien hai lan.
5. **Pipeline AI News chat che (Alpha Vantage -> Gemini -> PostgreSQL -> Room DB):**
   - Lay tin that tu Alpha Vantage `NEWS_SENTIMENT`.
   - Gui sang Google Gemini AI (`gemini-3.6-flash`) qua OpenAI-compatible endpoint de dich tieu de va tom tat 2 den 4 bullet tieng Viet, gan nhan tam ly va ly do danh gia.
   - Luu ket qua vao bang `NEWS_AI_CACHE` trong PostgreSQL de tai su dung, giam chi phi va do tre.
   - Android dong bo tin tuc va luu vao Room DB tren may, ho tro doc offline khi mat ket noi.
6. **Bao mat Production:**
   - Tren profile `prod`, `ProductionSecurityFilter` chan toan bo Swagger UI, OpenAPI spec, H2 Console va endpoint diagnostics bang HTTP 404.
   - Mat khau keystore, API keys va thong tin nhay cam khong duoc commit vao repository hoac in ra log.