-- V5__add_merchant_auth_columns.sql

ALTER TABLE merchants
ADD COLUMN email TEXT UNIQUE,
ADD COLUMN password_hash TEXT;

ALTER TABLE merchants
RENAME COLUMN name TO business_name;

-- Give dummy values to any existing rows so we can add NOT NULL constraints
UPDATE merchants
SET email = 'dummy_' || merchant_id || '@example.com',
    password_hash = '$2a$10$dummyhashdummyhashdummy'
WHERE email IS NULL;

ALTER TABLE merchants
ALTER COLUMN email SET NOT NULL,
ALTER COLUMN password_hash SET NOT NULL,
ALTER COLUMN connected_gateway DROP NOT NULL,
ALTER COLUMN encrypted_api_key DROP NOT NULL,
ALTER COLUMN encrypted_api_secret DROP NOT NULL;

