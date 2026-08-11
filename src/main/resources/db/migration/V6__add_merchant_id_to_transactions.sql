-- V6__add_merchant_id_to_transactions.sql

ALTER TABLE transactions
ADD COLUMN merchant_id UUID REFERENCES merchants(merchant_id);

CREATE INDEX idx_transactions_merchant ON transactions(merchant_id);
