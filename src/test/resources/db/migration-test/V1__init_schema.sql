-- V1__init_schema.sql (H2-compatible version for tests)
-- Core schema from ARCHITECTURE.md §6, adapted for H2 MODE=PostgreSQL

CREATE TABLE banks (
    bank_id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    historical_bd_rate NUMERIC(6,4),
    historical_td_rate NUMERIC(6,4),
    historical_deemed_approved_rate NUMERIC(6,4)
);

CREATE TABLE transactions (
    txn_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    remitter_bank_id UUID REFERENCES banks(bank_id),
    beneficiary_bank_id UUID REFERENCES banks(bank_id),
    amount_inr NUMERIC(12,2),
    state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    tat_deadline TIMESTAMP WITH TIME ZONE,
    penalty_start_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    penalty_amount_inr NUMERIC(10,2) DEFAULT 0,
    decline_code VARCHAR(255),
    order_reference VARCHAR(255)
);

CREATE INDEX idx_transactions_state ON transactions(state);
CREATE INDEX idx_transactions_remitter ON transactions(remitter_bank_id);
CREATE INDEX idx_transactions_beneficiary ON transactions(beneficiary_bank_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);

CREATE TABLE state_transitions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    txn_id UUID REFERENCES transactions(txn_id),
    from_state VARCHAR(50),
    to_state VARCHAR(50),
    transitioned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(1000)
);

CREATE INDEX idx_state_transitions_txn ON state_transitions(txn_id);

CREATE TABLE webhook_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    payload CLOB,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duplicate_of BIGINT REFERENCES webhook_events(id)
);

CREATE INDEX idx_webhook_events_idempotency ON webhook_events(idempotency_key);
