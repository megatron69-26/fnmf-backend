-- Migration V10: Add ai_shard to market_forecasts
-- Non-destructive, idempotent

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'market_forecasts'
    ) THEN
        ALTER TABLE market_forecasts ADD COLUMN IF NOT EXISTS ai_shard VARCHAR(50);
    END IF;
END $$;
