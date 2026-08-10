# Razorpay Gateway-Driven Resolution for PENALTY_ACCRUING Transactions

When a Razorpay-sourced transaction reaches `PENALTY_ACCRUING`, call Razorpay's real API to either confirm success or trigger a refund — instead of waiting for simulated NPCI batch resolution.

## Proposed Changes

### New State Machine Events & Transitions

We need two new events to distinguish gateway-driven resolutions from simulated batch resolutions:

#### [MODIFY] [TransactionEvent.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/domain/TransactionEvent.java)

Add two new enum values:
- `GATEWAY_STATUS_CHECK_SUCCESS` — Razorpay confirms payment actually succeeded
- `GATEWAY_REFUND_COMPLETED` — Razorpay refund API succeeded

#### [MODIFY] [TransactionState.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/domain/TransactionState.java)

No new states needed. The existing `SUCCESS` and `RESOLVED_REFUNDED` states are appropriate terminal states. The distinction is captured in the `state_transitions.reason` field.

#### [MODIFY] [StateMachine.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/domain/StateMachine.java)

Add two new transitions from `PENALTY_ACCRUING`:
```
PENALTY_ACCRUING + GATEWAY_STATUS_CHECK_SUCCESS → SUCCESS
PENALTY_ACCRUING + GATEWAY_REFUND_COMPLETED     → RESOLVED_REFUNDED
```

---

### Transaction Source Tracking

We need to know which transactions came from Razorpay vs the generic webhook endpoint.

#### [NEW] [V4__add_source_gateway_column.sql](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/resources/db/migration/V4__add_source_gateway_column.sql)

```sql
ALTER TABLE transactions ADD COLUMN source_gateway TEXT;
CREATE INDEX idx_transactions_source_gateway ON transactions(source_gateway);
```

#### [MODIFY] [Transaction.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/domain/Transaction.java)

Add `sourceGateway` field (nullable — null for non-connector transactions).

#### [MODIFY] [ConnectorWebhookController.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/api/ConnectorWebhookController.java)

Set `sourceGateway` on the normalized `WebhookRequest` so it flows through Kafka to the consumer.

#### [MODIFY] [WebhookRequest.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/api/WebhookRequest.java)

Add optional `sourceGateway` field.

#### [MODIFY] [TransactionProcessingService.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/domain/TransactionProcessingService.java)

Persist `sourceGateway` from the webhook request onto the Transaction entity.

---

### Razorpay API Client

#### [NEW] [RazorpayApiClient.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/RazorpayApiClient.java)

A Spring `RestClient`-based service that:
1. `fetchPaymentStatus(apiKey, apiSecret, paymentId)` → calls `GET https://api.razorpay.com/v1/payments/{id}` with Basic Auth, returns a `RazorpayPaymentStatus` record containing `status` (captured/failed/refunded/etc.) and `id`.
2. `initiateRefund(apiKey, apiSecret, paymentId)` → calls `POST https://api.razorpay.com/v1/payments/{id}/refund` with Basic Auth, returns a `RazorpayRefundResponse` record.

Uses Basic Auth: `Authorization: Basic base64(key_id:key_secret)`.

---

### Gateway Resolution Sweep (new sweep in scheduler)

#### [NEW] [GatewayResolutionService.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/GatewayResolutionService.java)

A new service invoked from `BatchResolutionScheduler` that:

1. Queries `PENALTY_ACCRUING` transactions where `source_gateway = 'razorpay'`.
2. For each, looks up the merchant by `connected_gateway = 'razorpay'` to get encrypted API credentials.
3. Decrypts credentials via `AesGcmEncryptor`.
4. Calls `RazorpayApiClient.fetchPaymentStatus()`.
5. **If status = `captured`**: transition to `SUCCESS` with reason `"Auto-corrected via Razorpay status check — payment confirmed successful"`.
6. **If status = `failed`/`created`/`authorized`**: call `RazorpayApiClient.initiateRefund()`, then transition to `RESOLVED_REFUNDED` with reason `"Auto-refunded via Razorpay API — refund_id: {id}"`.
7. **If status = `refunded`**: transition to `RESOLVED_REFUNDED` with reason `"Auto-corrected via Razorpay status check — already refunded"`.

#### [MODIFY] [BatchResolutionScheduler.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/scheduler/BatchResolutionScheduler.java)

Add a new **Sweep 5: Gateway resolution** that calls `GatewayResolutionService.resolveRazorpayTransactions()` before the existing sweeps — so gateway-sourced transactions get real API-driven resolution before the simulated batch rolls apply.

#### [MODIFY] [TransactionRepository.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/domain/TransactionRepository.java)

Add query: `findByStateAndSourceGateway(TransactionState state, String sourceGateway)`.

---

### Frontend — Resolution Reason Display

#### [MODIFY] [TransactionDetail.tsx](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/frontend/src/components/TransactionDetail.tsx)

The `reason` text is already rendered in the timeline. We'll add a visual indicator:
- If `step.reason` contains `"via Razorpay"` or `"via gateway"` → show a `⚡ Gateway Action` badge next to the reason.
- Otherwise → show `📦 NPCI Batch` badge (or nothing for non-resolution transitions).

This is purely display logic — no API changes needed.

---

### Tests

#### [NEW] [GatewayResolutionServiceTest.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/test/java/com/upi/reconcile/connectors/GatewayResolutionServiceTest.java)

Unit test with mocked `RazorpayApiClient`:

1. **Test: Razorpay status=captured → SUCCESS** — Create a `PENALTY_ACCRUING` transaction with `source_gateway=razorpay`, mock the API to return `captured`, verify transition to `SUCCESS` with gateway reason.

2. **Test: Razorpay status=failed → refund → RESOLVED_REFUNDED** — Mock API to return `failed`, then mock refund call, verify transition with refund reason.

3. **Test: Non-Razorpay transactions are skipped** — Create a `PENALTY_ACCRUING` transaction with `source_gateway=null`, verify no API call is made.

4. **Test: Razorpay API error is handled gracefully** — Mock API to throw, verify transaction stays in `PENALTY_ACCRUING`.

---

## Open Questions

> [!IMPORTANT]
> **Multiple merchants**: If multiple merchants have connected Razorpay, which merchant's credentials should we use to call the API? Currently the `Transaction` entity doesn't store which merchant it belongs to. For this implementation, I'll use `findByConnectedGateway("razorpay")` and pick the first one — since this is a demo/hackathon setup with one merchant per gateway. This would need a `merchant_id` FK on the `transactions` table for production.

## Verification Plan

### Automated Tests
- `./gradlew test --tests "com.upi.reconcile.connectors.GatewayResolutionServiceTest"` — mocked HTTP client unit tests
- `./gradlew compileJava compileTestJava` — compilation check
- `npx tsc --noEmit` — frontend type check

### Manual Verification
- Connect Razorpay, make a test payment via webhook, wait for it to reach `PENALTY_ACCRUING` in the demo timeline, then observe the scheduler auto-correcting it via the Razorpay API status check, with the gateway-specific reason showing in the state history timeline.
