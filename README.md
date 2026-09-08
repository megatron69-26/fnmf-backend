# ðŸš€ FNMF Backend & AI Gateway

> ðŸ“– **TÃ i liá»‡u Ä‘á»c code theo tá»«ng luá»“ng:** [FNMF Codebase Walkthrough](docs/codebase-walkthrough/README.md)

**Financial News & Market Forecasting (FNMF)**  
*Backend REST API, High-Integrity Financial Data Pipeline & AI Processing Layer*

- **NgÆ°á»i thá»±c hiá»‡n:** Äáº·ng Äá»©c KhÃ´i (Backend / Data Developer)
- **CÃ´ng nghá»‡ cá»‘t lÃµi:** Java 17, Spring Boot 3.3.5, Spring Data JPA, H2 Database (Local Dev) / PostgreSQL (Railway Production), Flyway Migrations, Spring Security (BCrypt), JJWT, Binance REST API, Alpha Vantage API, Google Gemini AI (OpenAI-compatible protocol), OpenAPI 3.0 / Swagger UI.
- **Base URL Local:** `http://localhost:8083`
- **Base URL Production (Railway):** `https://fnmf-backend-production.up.railway.app/`
- **Swagger UI (Local Dev):** `http://localhost:8083/swagger-ui.html`

---

## ðŸ“Œ 1. Báº£ng Ã¡nh xáº¡ Module, Endpoint & File mÃ£ nguá»“n (Source Code Mapping)

| Module | Chá»©c nÄƒng | PhÆ°Æ¡ng thá»©c & ÄÆ°á»ng dáº«n API | File Controller & Service |
| :--- | :--- | :--- | :--- |
| **Auth** | ÄÄƒng kÃ½ tÃ i khoáº£n Email-only | `POST /api/auth/register` | `AuthController.java` / `AuthService.java` |
| **Auth** | ÄÄƒng nháº­p tÃ i khoáº£n & Nháº­n JWT | `POST /api/auth/login` | `AuthController.java` / `AuthService.java` |
| **Auth** | Xem thÃ´ng tin User & Sá»‘ dÆ° | `GET /api/auth/me` | `AuthController.java` / `AuthService.java` |
| **Market** | GiÃ¡ thá»‹ trÆ°á»ng thá»i gian thá»±c (BTC, ETH, VÃ ng) | `GET /api/market/prices` | `MarketController.java` / `MarketDataService.java` |
| **Market** | GiÃ¡ 1 mÃ£ cá»¥ thá»ƒ (Authoritative check) | `GET /api/market/price/{symbol}` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Chuá»—i náº¿n tháº­t tá»« Binance Klines | `GET /api/market/candles?symbol=BTCUSDT` | `MarketController.java` / `MarketDataService.java` |
| **Market** | Tin tá»©c tÃ i chÃ­nh gá»‘c | `GET /api/market/news?limit=10` | `MarketController.java` / `MarketDataService.java` |
| **AI News** | **Pipeline AI News tá»± Ä‘á»™ng (Alpha Vantage + Gemini)** | `GET /api/news/feed?limit=5` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | PhÃ¢n tÃ­ch bÃ i bÃ¡o báº¥t ká»³ báº±ng AI | `POST /api/news/analyze` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Xem Cache bÃ i bÃ¡o trong CSDL | `GET /api/news/cache` | `NewsAiController.java` / `AiNewsService.java` |
| **AI News** | Äá»“ng bá»™ bÃ i bÃ¡o phá»¥c vá»¥ Mobile & Room DB | `GET /api/news/sync` | `NewsAiController.java` / `AiNewsService.java` |
| **Watchlist** | Láº¥y danh má»¥c theo dÃµi cá»§a User | `GET /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | **Báº£n tin AI chuyÃªn sÃ¢u cho cÃ¡c mÃ£ Watchlist** | `GET /api/watchlist/ai-insights` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | ThÃªm mÃ£ vÃ o danh má»¥c theo dÃµi | `POST /api/watchlist` | `WatchlistController.java` / `WatchlistService.java` |
| **Watchlist** | XÃ³a mÃ£ khá»i danh má»¥c theo dÃµi | `DELETE /api/watchlist/{symbol}` | `WatchlistController.java` / `WatchlistService.java` |
| **Trade** | Äáº·t lá»‡nh Paper Trading (Pessimistic Lock & Idempotency) | `POST /api/trade/order` | `TradeController.java` / `TradeService.java` |
| **Trade** | Tá»•ng quan tÃ i sáº£n & PnL thá»i gian thá»±c | `GET /api/trade/portfolio` | `TradeController.java` / `TradeService.java` |
| **Trade** | Lá»‹ch sá»­ cÃ¡c lá»‡nh Ä‘Ã£ khá»›p | `GET /api/trade/history` | `TradeController.java` / `TradeService.java` |
| **Forecast** | **Dá»± bÃ¡o xu hÆ°á»›ng & TÃ­n hiá»‡u AI cho 1 mÃ£** | `GET /api/forecast/{symbol}?timeframe=24H_7D` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Táº¡o / LÃ m má»›i báº£n dá»± bÃ¡o AI | `POST /api/forecast/analyze` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Lá»‹ch sá»­ cÃ¡c báº£n dá»± bÃ¡o AI cá»§a mÃ£ | `GET /api/forecast/history/{symbol}` | `ForecastController.java` / `ForecastService.java` |
| **Forecast** | Danh sÃ¡ch cÃ¡c báº£n dá»± bÃ¡o AI má»›i nháº¥t | `GET /api/forecast/latest` | `ForecastController.java` / `ForecastService.java` |

---

## ðŸ—„ï¸ 2. Cáº¥u trÃºc CÆ¡ sá»Ÿ dá»¯ liá»‡u & Quáº£n lÃ½ Migration (Flyway)

Há»‡ thá»‘ng há»— trá»£ cáº£ mÃ´i trÆ°á»ng phÃ¡t triá»ƒn cá»¥c bá»™ vÃ  Ä‘Ã¡m mÃ¢y:
- **Local Development / Test:** H2 Database (`jdbc:h2:file:./data/fnmf;DB_CLOSE_DELAY=-1;MODE=Oracle`).
- **Production (Railway):** PostgreSQL quáº£n lÃ½ qua cÃ¡c báº£n migration Flyway tá»± Ä‘á»™ng:
  - `V1__init_schema.sql`: Khá»Ÿi táº¡o báº£ng ngÆ°á»i dÃ¹ng, vÃ­ vá»‘n, danh má»¥c náº¯m giá»¯, lá»‹ch sá»­ giao dá»‹ch vÃ  tin tá»©c.
  - `V2__normalize_and_enforce_user_email.sql`: Chuáº©n hÃ³a mÃ´ hÃ¬nh email-only vÃ  rÃ ng buá»™c duy nháº¥t `LOWER(email)`.
  - `V3__holding_unique_constraint_and_client_order_id.sql`: RÃ ng buá»™c duy nháº¥t `(WALLET_ID, SYMBOL)` chá»‘ng nhÃ¢n Ä‘Ã´i tÃ i sáº£n vÃ  cá»™t `CLIENT_ORDER_ID` chá»‘ng trÃ¹ng láº·p lá»‡nh Ä‘á»“ng thá»i.

CÃ¡c báº£ng chÃ­nh:
1. `USERS`: LÆ°u trá»¯ tÃ i khoáº£n email-only, phÃ¢n quyá»n (`USER` / `ADMIN`), máº­t kháº©u bÄƒm BCrypt.
2. `WALLETS`: LÆ°u trá»¯ vÃ­ vá»‘n áº£o, sá»‘ dÆ° kháº£ dá»¥ng vÃ  vá»‘n khá»Ÿi táº¡o ($10,000.00). KhÃ³a bi quan (`PESSIMISTIC_WRITE`) khi Ä‘áº·t lá»‡nh.
3. `HOLDINGS`: Danh má»¥c tÃ i sáº£n áº£o Ä‘ang náº¯m giá»¯ (`BTCUSDT`, `ETHUSDT`, `XAUUSD`). KhÃ³a bi quan vÃ  rÃ ng buá»™c duy nháº¥t per wallet.
4. `TRANSACTIONS`: Lá»‹ch sá»­ cÃ¡c lá»‡nh Mua/BÃ¡n khá»›p lá»‡nh thá»i gian thá»±c, lÆ°u `CLIENT_ORDER_ID` Ä‘á»ƒ báº£o Ä‘áº£m Idempotency.
5. `WATCHLISTS`: Danh má»¥c theo dÃµi yÃªu thÃ­ch cá»§a tá»«ng ngÆ°á»i dÃ¹ng (Cloud CRUD), phÃ¢n tÃ¡ch Ä‘á»™c láº­p theo `userId`.
6. `NEWS_AI_CACHE`: Bá»™ nhá»› Ä‘á»‡m lÆ°u trá»¯ bÃ i bÃ¡o vÃ  káº¿t quáº£ tÃ³m táº¯t / phÃ¢n tÃ­ch tÃ¢m lÃ½ tá»« Gemini AI.
7. `MARKET_FORECASTS`: Báº£ng lÆ°u trá»¯ cÃ¡c báº£n dá»± bÃ¡o xu hÆ°á»›ng, vÃ¹ng há»— trá»£/khÃ¡ng cá»± vÃ  khuyáº¿n nghá»‹ Ä‘áº§u tÆ° tá»« AI (Cache 15 phÃºt).

---

## ðŸ›¡ï¸ 3. CÆ¡ cháº¿ Báº£o Ä‘áº£m ToÃ n váº¹n Dá»¯ liá»‡u & KhÃ´ng Sá»­ Dá»¥ng Dá»¯ Liá»‡u Giáº£ (Zero-Fake Policy)

1. **Nguá»“n dá»¯ liá»‡u thá»‹ trÆ°á»ng Authoritative:**
   - GiÃ¡ vÃ  náº¿n thá»i gian thá»±c láº¥y trá»±c tiáº¿p tá»« **Binance REST API** (`/api/v3/ticker/24hr`, `/api/v3/klines`).
   - TÃ¡ch biá»‡t rÃµ rÃ ng qua `MarketSymbolConfig` vÃ  `BinanceMarketClient`.
2. **ChÃ­nh sÃ¡ch Symbol Há»£p lá»‡ & Loáº¡i bá» USOIL:**
   - CÃ¡c mÃ£ há»£p lá»‡: `BTCUSDT`, `ETHUSDT`, `XAUUSD` (tham chiáº¿u `PAXGUSDT`).
   - MÃ£ `USOIL` vÃ  cÃ¡c mÃ£ láº¡ bá»‹ loáº¡i bá» hoÃ n toÃ n khá»i `/api/market/prices` vÃ  giao dá»‹ch; tráº£ HTTP 422 `UNSUPPORTED_SYMBOL`. Tuyá»‡t Ä‘á»‘i khÃ´ng bao giá» fallback vá» BTC.
3. **XÃ³a bá» hoÃ n toÃ n Mock/Random Data:**
   - ÄÃ£ loáº¡i bá» triá»‡t Ä‘á»ƒ `generateFallbackCandles()`, `Math.random()`, sin wave mÃ´ phá»ng vÃ  giÃ¡ fallback hardcode trong toÃ n bá»™ production backend.
   - Khi máº¥t káº¿t ná»‘i nhÃ  cung cáº¥p vÃ  chÆ°a tá»«ng cÃ³ cache tháº­t: Tráº£ HTTP 503 `DATA_UNAVAILABLE`.
   - Náº¿u cÃ³ cache tháº­t cá»§a chÃ­nh mÃ£ Ä‘Ã³: Tráº£ cache tháº­t cuá»‘i cÃ¹ng kÃ¨m cá» `stale=true`.
4. **Idempotency & KhÃ³a giao dá»‹ch Ä‘á»“ng thá»i (Pessimistic Locking):**
   - Äáº·t lá»‡nh sá»­ dá»¥ng `@Lock(LockModeType.PESSIMISTIC_WRITE)` trÃªn `Wallet` vÃ  `Holding` Ä‘á»ƒ ngÄƒn cháº·n race condition khi nhiá»u lá»‡nh gá»­i cÃ¹ng lÃºc.
   - Há»— trá»£ `clientOrderId` (UUID): Tá»± Ä‘á»™ng phÃ¡t hiá»‡n vÃ  replay káº¿t quáº£ lá»‡nh cÅ© náº¿u nháº­n trÃ¹ng mÃ£ yÃªu cáº§u, khÃ´ng trá»« tiá»n hai láº§n.
5. **Watchlist Cloud CÃ¡ nhÃ¢n hÃ³a:**
   - Há»— trá»£ Ä‘áº§y Ä‘á»§ CRUD cÃ¡ nhÃ¢n hÃ³a (`GET`, `POST`, `DELETE /api/watchlist`) dá»±a trÃªn JWT Bearer Token.
   - LÃ m giÃ u dá»¯ liá»‡u giÃ¡ trá»±c tuyáº¿n tá»± Ä‘á»™ng trÆ°á»›c khi tráº£ vá» client.