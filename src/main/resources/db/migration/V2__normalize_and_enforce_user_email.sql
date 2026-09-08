-- Migration V2: Normalize and enforce case-insensitive unique email for USERS
-- Step 1: Check for duplicates under LOWER(TRIM(EMAIL)) before applying changes
DO $$
DECLARE
    dup_count INTEGER;
    dup_emails TEXT;
BEGIN
    SELECT COUNT(*), string_agg(dup_email, ', ')
    INTO dup_count, dup_emails
    FROM (
        SELECT LOWER(TRIM(EMAIL)) AS dup_email
        FROM USERS
        WHERE EMAIL IS NOT NULL
        GROUP BY LOWER(TRIM(EMAIL))
        HAVING COUNT(*) > 1
    ) dups;

    IF dup_count > 0 THEN
        RAISE EXCEPTION 'Flyway Migration V2 Halted: % duplicate email group(s) detected: [%]. Duplicate resolution required before applying unique constraint.', dup_count, dup_emails;
    END IF;
END $$;

-- Step 2: Normalize existing email data to lowercase and trimmed (preserves legacy non-email strings like khoi10)
UPDATE USERS
SET EMAIL = LOWER(TRIM(EMAIL))
WHERE EMAIL IS NOT NULL AND EMAIL <> LOWER(TRIM(EMAIL));

-- Step 3: Create unique case-insensitive index on LOWER(EMAIL)
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower ON USERS (LOWER(EMAIL));
