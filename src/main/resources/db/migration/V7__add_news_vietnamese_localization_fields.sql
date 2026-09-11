-- Migration V7: Add Vietnamese localization fields for news title and bullet points
-- Non-destructive, idempotent, preserves all existing cached news articles

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'news_ai_cache'
    ) THEN
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS display_title_vi VARCHAR(500);
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS display_summary_vi TEXT;
        ALTER TABLE news_ai_cache ADD COLUMN IF NOT EXISTS bullet_points_vi TEXT;
    END IF;
END $$;
