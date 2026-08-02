-- V2__add_ml_classification_columns.sql
-- Adds ML mismatch classifier output columns to transactions table.

ALTER TABLE transactions ADD COLUMN ml_classification TEXT;
ALTER TABLE transactions ADD COLUMN ml_confidence NUMERIC(5,4);

COMMENT ON COLUMN transactions.ml_classification IS 'ML classifier prediction: stuck_payment|wrong_amount|duplicate_charge|no_mismatch';
COMMENT ON COLUMN transactions.ml_confidence IS 'Model confidence score for the predicted classification (0.0 - 1.0)';
