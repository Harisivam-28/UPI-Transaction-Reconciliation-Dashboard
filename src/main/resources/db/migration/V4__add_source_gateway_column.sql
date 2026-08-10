-- V4__add_source_gateway_column.sql
-- Tracks which payment gateway connector (razorpay, payu, cashfree) created
-- each transaction. NULL for transactions from the generic /api/webhooks/transaction endpoint.

ALTER TABLE transactions ADD COLUMN source_gateway TEXT;

CREATE INDEX idx_transactions_source_gateway ON transactions(source_gateway);
