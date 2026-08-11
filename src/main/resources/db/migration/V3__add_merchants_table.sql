-- V3__add_merchants_table.sql
-- Adds the merchants table for payment gateway connector onboarding.
-- API key and secret columns store AES-256-GCM encrypted values.

CREATE TABLE merchants (
    merchant_id         UUID PRIMARY KEY,
    name                TEXT NOT NULL,
    connected_gateway   TEXT NOT NULL,
    encrypted_api_key   TEXT NOT NULL,
    encrypted_api_secret TEXT NOT NULL,
    webhook_secret      TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_merchants_gateway ON merchants(connected_gateway);
