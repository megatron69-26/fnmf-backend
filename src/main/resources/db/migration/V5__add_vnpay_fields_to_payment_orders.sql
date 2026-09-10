-- Migration V5: Add VNPay sandbox fields to payment_orders
-- Non-destructive, idempotent, preserves all existing user balances and data

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_schema = current_schema() AND lower(table_name) = 'payment_orders'
    ) THEN
        ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS amount_vnd NUMERIC(18,0);
        ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS exchange_rate_snapshot NUMERIC(18,4);
        ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS provider_transaction_no VARCHAR(100);
        ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS provider_response_code VARCHAR(50);
        ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;
    END IF;
END $$;
