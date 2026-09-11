# DU AN FNMF BACKEND - TAI LIEU BAN GIAO VA TRANG THAI TOAN DIEN (PROJECT STATUS & HANDOVER)

Danh cho Lap trinh vien va AI Agent ke thua:
File nay chua toan bo thong tin kien truc, cau hinh moi truong, tai khoan ket noi, trang thai cac module da hoan thanh va huong dan tiep tuc du an.
Quy tac bat buoc: Moi thay doi hoac cap nhat code trong tuong lai phai duoc cap nhat lai vao file nay.

---

## 1. THONG TIN THANH VIEN VA DU AN
* Ten do an: FNMF - Financial News & Market Forecasting (Ung dung Tin tuc Tai chinh, Phan tich Cam xuc AI & Giao dich Gia lap).
* Thanh vien phu trach Backend/Data: Dang Duc Khoi (Member #3).
* Truong nhom / PO / AI Prompt Engineer: Nguyen Huu Manh (Member #1).
* Thanh vien Android Mobile App: Nguyen Quang Hung (Member #2).
* GitHub Repository: https://github.com/megatron69-26/fnmf-backend
* Thu muc du an tren may tinh:
  * `llm-gateway3` (Thu muc ma nguon Backend hien hanh)

---

## 2. THONG SO MOI TRUONG VA CAU HINH HE THONG
* Ngon ngu & Framework: Java 17 (Adoptium OpenJDK), Spring Boot 3.3.5, Maven.
* Cong chay Server: `http://localhost:8083`
* Giao dien Tai lieu Swagger UI (moi truong phat trien): `http://localhost:8083/swagger-ui.html`
* Co so du lieu:
  * Production: PostgreSQL tren Railway.
  * Local / Testing: H2 in-memory che do tuong thich PostgreSQL (`MODE=PostgreSQL`).
  * Cac bang CSDL (10 bang quan ly boi Flyway V1 - V8): `USERS`, `WALLETS`, `HOLDINGS`, `TRANSACTIONS`, `WATCHLISTS`, `NEWS_AI_CACHE`, `MARKET_FORECASTS`, `PAYMENT_ORDERS`, `WALLET_LEDGER`, `PAYMENT_EVENTS`.
* API Keys & Dich vu ben ngoai:
  * Binance REST API (Klines & Ticker): `https://api.binance.com`
  * Alpha Vantage API (Real News): `https://www.alphavantage.co/query`
  * Google Gemini AI: Model `gemini-3.6-flash` qua OpenAI-compatible endpoint (`https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`).

---

## 3. TIEN DO VA TRANG THAI CAC MODULE

### TUAN 1: HE THONG XAC THUC, PHAN QUYEN VA VI TIEN AO ($10,000)
1. `POST /api/auth/register`: Dang ky tai khoan Email-only, ma hoa mat khau bang `BCryptPasswordEncoder` (Salt 10 vong), tu dong khoi tao Vi ao $10,000 von ban dau trong bang `WALLETS`.
2. `POST /api/auth/login`: Dang nhap, kiem tra mat khau bam, sinh chuoi `JWT Token` (HMAC-SHA256) co thoi han 24 gio.
3. `GET /api/auth/me`: Lay thong tin ca nhan va so du vi bang Bearer Token.

---

### TUAN 2: DU LIEU THI TRUONG, AI PHAN TICH TIN TUC, WATCHLIST VA PAPER TRADING

#### Module 1: Du lieu Thi truong va Nen Nhat (Market Data & Candlesticks)
* `GET /api/market/prices`: Lay gia thoi gian thuc cua Bitcoin (`BTCUSDT`), Ethereum (`ETHUSDT`), Vang (`XAUUSD` tham chieu `PAXGUSDT`). Ma `USOIL` va cac ma khong ho tro bi tu choi hoan toan voi HTTP 422 `UNSUPPORTED_SYMBOL`, khong fallback ve BTC.
* `GET /api/market/price/{symbol}`: Lay gia chi tiet cua 1 ma tai san (Authoritative check).
* `GET /api/market/candles?symbol=BTCUSDT`: Lay chuoi 30 cay nen OHLCV (Open, High, Low, Close, Volume) that tu Binance REST API phuc vu Android ve bieu do nen MPAndroidChart.
* Co che bao ve: In-Memory Cache (TTL 30s) chong tran Rate Limit. Khong su dung bat ky ham sinh nen toan hoc mo phong hay gia hardcode nao tren production backend.

#### Module 2: Pipeline Tin tuc That va AI Phan tich Cam xuc (AI News Sentiment & PostgreSQL Cache)
* Luong xu ly: Alpha Vantage -> Gemini -> PostgreSQL NEWS_AI_CACHE -> Android Room DB.
* `GET /api/news/sync?limit=5` va `GET /api/news/feed?limit=5`:
  1. Gioi han limit dong nhat tu 1 den 20 (`DEFAULT_LIMIT = 5`, `MAX_LIMIT = 20`). Ngoai pham vi tra ve HTTP 400 Bad Request.
  2. Alpha Vantage fetch toi da 50 bai (`MAX_ALPHA_FETCH = 50`, `Math.min(limit * 3, 50)`).
  3. Lay tin that tu Alpha Vantage (`NEWS_SENTIMENT`).
  4. Dua qua Google Gemini AI (`gemini-3.6-flash`) voi System Prompt chuyen gia tai chinh de dich tieu de sang tieng Viet tu nhien va tom tat 2 den 4 gach dau dong su kien that.
  5. Tu dong luu ket qua vao bang `NEWS_AI_CACHE` trong PostgreSQL.
  6. Tra ve bai bao hoan chinh (tieu de tieng Viet, 2-4 bullet tieng Viet, publisher, author, thoi gian, anh bia).
* Co che bao ve:
  * Cache CSDL PostgreSQL: Cac bai da co ban dich tieng Viet hop le duoc phuc vu truc tiep tu CSDL ma khong can goi lai Gemini AI. Cache freshness xac dinh DUY NHAT theo `analyzedAt` (thoi diem AI phan tich), khong fallback sang `publishedAt`.
  * Dieu phoi AlphaNewsCoordinator & Single-Flight:
    - Khoa `refreshLock` bao phu toan bo pipeline.
    - Luu `CachedAlphaSnapshot` bat bien trong RAM.
    - Bao toan trang thai `SUCCESS_EMPTY`: Khi Alpha tra 200 feed=[], cache snapshot va replay `empty` trong 90 phut, khong bien thanh `degraded`.
    - Gemini Failure Cooldown (10 phut): Khi Alpha thanh cong nhung Gemini loi, luu raw snapshot va dat cooldown 10 phut cho Gemini; request trong cooldown khong goi lai Gemini; het cooldown retry Gemini bang raw snapshot ma khong dot quota Alpha.
  * Xu ly loi va Graceful Degradation:
    - Neu da co tin tieng Viet hop le trong Cache: tra ve tu Cache voi `status="ok"`.
    - Neu khong co Cache va Alpha khong co tin: tra ve `status="empty"` minh bach.
    - Neu khong co Cache va dich vu gap loi mang/rate limit/timeout: tra ve `status="degraded"` minh bach.
    - Invariant bat buoc: `NewsSyncResult.ok` luon co data khong rong, cam tao `ok` voi danh sach rong/null o moi constructor va setter.
    - Tuyet doi khong log hoac ro ri Alpha Vantage API key.
    - Tuyet doi khong dung Regex thay the tu ngu, khong dung Heuristic bia dat noi dung, khong sinh du lieu gia.
* `POST /api/news/analyze`: Phan tich bai bao tuy chinh.

#### Module 3: Quan ly Danh muc Theo doi (Watchlist CRUD)
* `GET /api/watchlist`: Lay danh muc ca nhan cua User (Bearer Token), tu dong ghep gia thi truong va bien dong 24h tu Binance.
* `GET /api/watchlist/ai-insights`: Quet toan bo cac ma trong Watchlist cua User de goi Gemini AI lay du bao chien luoc va tin tuc tom tat.
* `POST /api/watchlist`: Them ma tai san moi (Body: `{ "symbol": "ETHUSDT" }`), kiem tra chong trung lap.
* `DELETE /api/watchlist/{symbol}`: Xoa ma khoi danh muc theo doi.

#### Module 4: Giao dich Gia lap va Quan ly Danh muc (Paper Trading & Realtime PnL)
* `POST /api/trade/order`: Dat lenh MUA (`BUY`) hoac BAN (`SELL`) theo gia thi truong thoi gian thuc authoritative cua backend.
  * Kiem tra so du vi kha dung (chong am tien).
  * Tinh Gia mua trung binh (DCA): `newAvgPrice = (oldCost + newCost) / (oldQty + newQty)`.
  * Dam bao tinh toan ven ACID (`@Transactional`), khoa bi quan `@Lock(PESSIMISTIC_WRITE)` tren vi va tai san nam giu trong PostgreSQL.
  * Ho tro `clientOrderId` (UUID) dam bao tinh luy thua (Idempotency), replay ket qua cu neu nhan trung yeu cau.
* `GET /api/trade/portfolio`: Lay tong quan tai san rong (Net Worth = Tien mat + Gia tri cac ma dang nam giu) va Loi/Lo (PnL) thoi gian thuc.
* `GET /api/trade/history`: Lay toan bo lich su cac lenh Mua/Ban da khop trong bang `TRANSACTIONS`.

---

### TUAN 3: TINH NANG NANG CAO VA DU BAO THI TRUONG AI

#### Module 1: Du bao Xu huong va Tin hieu Giao dich AI (AI Market Forecasting)
* `GET /api/forecast/{symbol}?timeframe=24H_7D`: Lay phan tich du bao xu huong da chieu cho ma tai san.
* `POST /api/forecast/analyze`: Tao hoac lam moi ban du bao thi truong AI (Body: `{ "symbol": "XAUUSD" }`).
* `GET /api/forecast/history/{symbol}`: Xem lich su cac ban du bao AI trong qua khu cua ma tai san.
* `GET /api/forecast/latest`: Lay cac ban du bao AI moi nhat trong CSDL PostgreSQL.
* Co che hoat dong: Ket hop 30 nen ky thuat tu Binance Klines va tin tuc vi mo gan nhat gui sang Gemini AI (`gemini-3.6-flash`) de xac dinh Vung Ho tro (Support), Khang cu (Resistance), Xu huong va Khuyen nghi (`STRONG_BUY`, `BUY`, `HOLD`, `SELL`, `STRONG_SELL`).
* Co che bao ve: Cache CSDL PostgreSQL 15 phut (bang `MARKET_FORECASTS`), phan loai ro rang nguon du bao qua cot `source` (AI_GEMINI hoac HEURISTIC_FALLBACK) theo Flyway V8.

---

### TUAN 4: CONG THANH TOAN SANDBOX & SO CAI BIEN DONG SO DU (SIMULATED BANKING)
1. Kien truc Module Payment Sandbox:
   * Chinh sach Zero-Risk: Toan bo giao dich la tien USD mo phong (Sandbox), khong tich hop cong the that va khong luu tru thong tin nhay cam (the tin dung, CVV, OTP).
   * Provider-Neutral Architecture: Phan tach ro tang nghiep vu (`PaymentService`), tang dinh tuyen (`PaymentProviderRegistry`), va nha cung cap sandbox noi bo (`InternalSandboxPaymentProvider`).
   * So cai bien dong so du don (Single-Entry Append-Only Ledger): Luu tru moi bien dong so du do Nap (`DEPOSIT`), Rut (`WITHDRAWAL`), Dieu chinh Admin (`ADMIN_ADJUSTMENT`), va Khop lenh (`TRADE_BUY`, `TRADE_SELL`) vao bang `WALLET_LEDGER` (`balance_before`, `balance_after`, `amount_usd`, `entry_type`, `reference_id`).
   * Idempotency & Concurrency: Khoa bi quan `@Lock(PESSIMISTIC_WRITE)`, cot `client_request_id` chong trung lap, bang `PAYMENT_EVENTS`, va token thanh toan co han 15 phut (qua han tra ve HTTP 410 GONE).
2. Swagger UI / OpenAPI 3.0 Integration:
   * Tu dong sinh tai lieu tren dev/local tai `/swagger-ui.html` voi tinh nang Authorize JWT Bearer Token.
   * `ProductionSecurityFilter` chan Swagger, OpenAPI docs, h2-console, va endpoint diagnostics tren profile `prod` bang HTTP 404.
3. CORS & Global Exception Handling:
   * Cau hinh CORS mo trong `AppConfig.java` cho Android va Web Client.
   * `GlobalExceptionHandler.java` bat va chuan hoa ma loi thanh JSON nhat quan.

---

## 4. BO CAU HOI BAO VE DO AN (DEFENSE Q&A CHEATSHEET)

| Cau hoi | Cach tra loi chuan | Vi tri code tuong doi |
| :--- | :--- | :--- |
| **1. Mat mang Gemini thi he thong xu ly ra sao?** | He thong ap dung Graceful Degradation: uu tien phuc vu cac ban tin da co ban dich tieng Viet chuan trong CSDL PostgreSQL; neu khong co cache va provider loi thi tra ve trang thai degraded/empty minh bach, tuyet doi khong crash app va khong bia dat du lieu. | `src/main/java/com/llmgateway/service/AiNewsService.java` |
| **2. Toi uu chi phi va do tre AI?** | Cache CSDL PostgreSQL tai bang `NEWS_AI_CACHE` va `MARKET_FORECASTS`. Cac bai da duoc phan tich se duoc tai truc tiep tu CSDL voi co `fromCache: true`, chi goi AI khi phat hien bai bao moi hoac het han cache. | `src/main/java/com/llmgateway/service/AiNewsService.java`, `ForecastService.java` |
| **3. Alpha Vantage va Binance xu ly gioi han rate limit?** | In-Memory Cache (TTL 30s) trong `MarketDataService` de tai su dung du lieu nen va gia. Luu snapshot Alpha trong RAM kem cooldown 10 phut cho Gemini khi xay ra loi. | `src/main/java/com/llmgateway/service/MarketDataService.java`, `AiNewsService.java` |
| **4. Tinh toan ven khop lenh vi ao?** | Su dung `@Transactional` ket hop khoa bi quan `@Lock(PESSIMISTIC_WRITE)` tren `Wallet` va `Holding`, kiem tra so du nghiem ngat va ghi so cai kiem toan `WALLET_LEDGER`. | `src/main/java/com/llmgateway/service/TradeService.java` |
| **5. Cong thuc tinh DCA va PnL?** | `newAvgPrice = (oldCost + newCost) / newQty`. PnL = `(CurrentPrice - AvgPrice) * Qty`. | `src/main/java/com/llmgateway/service/TradeService.java` |
| **6. Mo hinh Du bao AI hoat dong the nao?** | Ket hop du lieu 30 nen ky thuat va tin tuc vi mo qua Gemini 3.6 Flash de xac dinh Ho tro/Khang cu va khuyen nghi giao dich; phan biet ro rang nguon AI_GEMINI vs HEURISTIC qua cot source trong CSDL. | `src/main/java/com/llmgateway/service/ForecastService.java` |
| **7. Quan ly tai lieu API va bao mat production?** | OpenAPI 3.0 / Swagger UI phuc vu dev/local; tren production, `ProductionSecurityFilter` chan cac endpoint nhay cam (Swagger, H2, DB query, diagnostics) bang HTTP 404. | `src/main/java/com/llmgateway/filter/ProductionSecurityFilter.java` |

---

## 5. HUONG DAN DANH CHO AGENT TIEP THEO (AI AGENT INSTRUCTIONS)
1. Doc ky file nay truoc khi tiep tuc phat trien hoac bao tri du an.
2. Local database mac dinh la H2 in-memory (`MODE=PostgreSQL`), port mac dinh la 8083.
3. Khi chinh sua ma nguon, luon kiem tra bang `mvn test` va `git diff --check` de dam bao khong phat sinh loi.
4. Sau khi hoan thanh tinh nang moi, hay cap nhat lai file `PROJECT_STATUS.md` nay.
