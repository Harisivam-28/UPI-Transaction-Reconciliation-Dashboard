-- V1__init_schema.sql
-- Core schema from ARCHITECTURE.md §6

CREATE TABLE banks (
    bank_id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    historical_bd_rate NUMERIC(6,4),
    historical_td_rate NUMERIC(6,4),
    historical_deemed_approved_rate NUMERIC(6,4)
);

CREATE TABLE transactions (
    txn_id UUID PRIMARY KEY,
    idempotency_key TEXT UNIQUE NOT NULL,
    remitter_bank_id UUID REFERENCES banks(bank_id),
    beneficiary_bank_id UUID REFERENCES banks(bank_id),
    amount_inr NUMERIC(12,2),
    state TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    tat_deadline TIMESTAMPTZ,
    penalty_start_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    penalty_amount_inr NUMERIC(10,2) DEFAULT 0,
    decline_code TEXT,
    order_reference TEXT
);

CREATE INDEX idx_transactions_state ON transactions(state);
CREATE INDEX idx_transactions_remitter ON transactions(remitter_bank_id);
CREATE INDEX idx_transactions_beneficiary ON transactions(beneficiary_bank_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);

CREATE TABLE state_transitions (
    id BIGSERIAL PRIMARY KEY,
    txn_id UUID REFERENCES transactions(txn_id),
    from_state TEXT,
    to_state TEXT,
    transitioned_at TIMESTAMPTZ NOT NULL,
    reason TEXT
);

CREATE INDEX idx_state_transitions_txn ON state_transitions(txn_id);

CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key TEXT NOT NULL,
    payload JSONB,
    received_at TIMESTAMPTZ NOT NULL,
    duplicate_of BIGINT REFERENCES webhook_events(id)
);

CREATE INDEX idx_webhook_events_idempotency ON webhook_events(idempotency_key);
