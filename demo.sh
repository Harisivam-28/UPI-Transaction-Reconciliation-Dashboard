#!/bin/bash

# Bank UUIDs based on the DB
REMITTER="9f948a2e-8081-3529-9139-a7d7cbed0bcb" # Airtel Payments Bank
BENEFICIARY="36c8a362-b8b5-3be8-be37-b27c4e65f2a6" # Axis Bank

echo "1. Generating standard transactions..."
for i in {1..3}; do
  curl -s -X POST -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"demo-txn-$i-$RANDOM\", \"remitterBankId\":\"$REMITTER\", \"beneficiaryBankId\":\"$BENEFICIARY\", \"amountInr\":$((1000 * i)), \"orderReference\":\"ORD-DEMO-$i\"}" \
    http://localhost:8080/api/webhooks/transaction > /dev/null
done

sleep 3 # Wait for Kafka consumer to persist them

echo "2. Simulating a severe TAT Breach for Penalty Accrual..."
# We force one transaction's deadline to be 5 days in the past
# The BatchResolutionScheduler sweeps every 7 seconds, so it will instantly pick this up!
docker exec upi-postgres psql -U postgres -d upi_reconcile -c "
UPDATE transactions 
SET tat_deadline = NOW() - INTERVAL '5 days', 
    state = 'PENDING_RECONCILIATION'
WHERE idempotency_key LIKE 'demo-txn-3-%';
"

echo "3. Generating a burst of failed transactions to trigger the Anomaly Detection..."
for i in {10..40}; do
  curl -s -X POST -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"demo-burst-$i-$RANDOM\", \"remitterBankId\":\"$REMITTER\", \"beneficiaryBankId\":\"$BENEFICIARY\", \"amountInr\":$((50 * i)), \"orderReference\":\"ORD-BURST-$i\"}" \
    http://localhost:8080/api/webhooks/transaction > /dev/null
done

sleep 3

# Force them to FAILED so the Rolling Anomaly Monitor flags the bank
docker exec upi-postgres psql -U postgres -d upi_reconcile -c "
UPDATE transactions 
SET state = 'TECHNICAL_DECLINED'
WHERE idempotency_key LIKE 'demo-burst-%';
"

echo "Demo data generation complete! The backend schedulers will now process these."
