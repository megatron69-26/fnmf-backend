-- Migration V6: Add news metadata and original summary fields to news_ai_cache
-- Non-destructive, idempotent, preserves all existing cached news articles

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'news_ai_cache'
    ) THEN
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS author VARCHAR(255);
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS source VARCHAR(255);
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS original_summary TEXT;
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS banner_image VARCHAR(500);
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS original_title VARCHAR(500);
    END IF;
END $$;
