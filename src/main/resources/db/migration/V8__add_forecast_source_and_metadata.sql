-- Migration V8: Add analysis_source and candle_count to market_forecasts
-- Non-destructive, idempotent

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'market_forecasts'
    ) THEN
        ALTER TABLE market_forecasts ADD COLUMN IF NOT EXISTS analysis_source VARCHAR(50);
        ALTER TABLE market_forecasts ADD COLUMN IF NOT EXISTS candle_count INTEGER;
    END IF;
END $$;
