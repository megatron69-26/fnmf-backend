-- Migration V11: Deduplicate WATCHLISTS and create UNIQUE index on (USER_ID, SYMBOL)

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'watchlists'
    ) THEN
        -- 1. Chuẩn hóa symbol về canonical symbol tương ứng theo MarketSymbolConfig
        UPDATE watchlists
        SET symbol = CASE
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('BTC', 'BTC/USDT', 'BTCUSDT', 'BTCUSD') THEN 'BTCUSDT'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('ETH', 'ETH/USDT', 'ETHUSDT', 'ETHUSD') THEN 'ETHUSDT'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('XAU', 'XAUUSD', 'XAU/USD', 'PAXG', 'PAXGUSDT', 'GOLD') THEN 'XAUUSD'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('BNB', 'BNB/USDT', 'BNBUSDT', 'BNBUSD') THEN 'BNBUSDT'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('SOL', 'SOL/USDT', 'SOLUSDT', 'SOLUSD') THEN 'SOLUSDT'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('XRP', 'XRP/USDT', 'XRPUSDT', 'XRPUSD') THEN 'XRPUSDT'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('ADA', 'ADA/USDT', 'ADAUSDT', 'ADAUSD') THEN 'ADAUSDT'
            WHEN UPPER(REPLACE(REPLACE(TRIM(symbol), '-', ''), '_', '')) IN ('DOGE', 'DOGE/USDT', 'DOGEUSDT', 'DOGEUSD') THEN 'DOGEUSDT'
            ELSE UPPER(TRIM(symbol))
        END;

        -- 2. Xóa các bản ghi trùng lặp (USER_ID, SYMBOL), chỉ giữ lại bản ghi có ID lớn nhất (mới nhất)
        DELETE FROM watchlists
        WHERE id NOT IN (
            SELECT MAX(id)
            FROM watchlists
            GROUP BY user_id, symbol
        );

        -- 3. Tạo Unique Index đảm bảo không bao giờ sinh duplicate (USER_ID, SYMBOL)
        CREATE UNIQUE INDEX IF NOT EXISTS uk_watchlists_user_symbol ON watchlists (user_id, symbol);
    END IF;
END $$;
