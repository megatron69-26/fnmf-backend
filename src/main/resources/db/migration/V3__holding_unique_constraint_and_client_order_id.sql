-- Migration V3: Unique constraint on HOLDINGS(WALLET_ID, SYMBOL) and CLIENT_ORDER_ID on TRANSACTIONS

-- Step 1: Pre-check duplicates in HOLDINGS before applying unique index
DO $$
DECLARE
    dup_count INTEGER;
    dup_details TEXT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'holdings'
    ) THEN
        SELECT COUNT(*), string_agg(wallet_id || ':' || symbol, ', ')
        INTO dup_count, dup_details
        FROM (
            SELECT wallet_id, symbol
            FROM holdings
            GROUP BY wallet_id, symbol
            HAVING COUNT(*) > 1
        ) dups;

        IF dup_count > 0 THEN
            RAISE EXCEPTION 'Flyway Migration V3 Halted: % duplicate holding(s) detected: [%]. Duplicate resolution required before applying unique constraint.', dup_count, dup_details;
        END IF;
    END IF;
END $$;

-- Step 2: Create unique index on HOLDINGS(WALLET_ID, SYMBOL) if table exists
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'holdings'
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uk_holding_wallet_symbol ON holdings (wallet_id, symbol);
    END IF;
END $$;

-- Step 3: Add CLIENT_ORDER_ID column to TRANSACTIONS and create UNIQUE index
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'transactions'
    ) THEN
        ALTER TABLE transactions ADD COLUMN IF NOT EXISTS client_order_id VARCHAR(64);
        -- Drop non-unique index if created earlier
        DROP INDEX IF EXISTS idx_transactions_wallet_client_order_id;
        CREATE UNIQUE INDEX IF NOT EXISTS uk_transactions_wallet_client_order_id 
        ON transactions (wallet_id, client_order_id) 
        WHERE client_order_id IS NOT NULL;
    END IF;
END $$;

