# Add Payment Gateway Connectors Module

Add a new `connectors` package to the existing Spring Boot backend that normalizes incoming payment gateway webhooks (Razorpay, PayU, Cashfree) into the existing `WebhookRequest` internal schema and feeds them into the `/api/webhooks/transaction` ingestion pipeline. Also add a `merchants` table with AES-256-GCM encrypted credentials and REST endpoints for merchant onboarding.

## Proposed Changes

### 1. Encryption Utility — `AesGcmEncryptor`

No existing encryption utility in the codebase — will create a new one.

#### [NEW] [AesGcmEncryptor.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/crypto/AesGcmEncryptor.java)

- AES-256-GCM with 12-byte random IV prepended to ciphertext
- 256-bit key configured via `app.encryption.aes-key` property (Base64-encoded)
- `encrypt(plaintext) → Base64(IV || ciphertext || tag)` and `decrypt(encoded) → plaintext`
- Spring `@Component` so it can be injected into services

---

### 2. Merchants Table & Entity

#### [NEW] [V3__add_merchants_table.sql](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/resources/db/migration/V3__add_merchants_table.sql)

```sql
CREATE TABLE merchants (
    merchant_id UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    connected_gateway TEXT NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    encrypted_api_secret TEXT NOT NULL,
    webhook_secret TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### [NEW] [Merchant.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/domain/Merchant.java)

JPA entity mapping to the `merchants` table.

#### [NEW] [MerchantRepository.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/domain/MerchantRepository.java)

Spring Data JPA repository for `Merchant`.

---

### 3. Connector Interface & Implementations

#### [NEW] [PaymentGatewayConnector.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/PaymentGatewayConnector.java)

```java
public interface PaymentGatewayConnector {
    WebhookRequest normalizeWebhookPayload(Map<String, Object> rawPayload);
    String gatewayName(); // "razorpay", "payu", "cashfree"
}
```

#### [NEW] [RazorpayConnector.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/RazorpayConnector.java)

Maps real Razorpay test-mode webhook payloads (`payment.captured`, `payment.failed`) to `WebhookRequest`:

| Razorpay field | → Internal field |
|---|---|
| `payload.payment.entity.id` (razorpay_payment_id) | `idempotencyKey` |
| `payload.payment.entity.amount` (in paise) | `amountInr` (÷ 100) |
| `payload.payment.entity.order_id` | `orderReference` |
| `payload.payment.entity.error_code` | `declineCode` (mapped: `BAD_REQUEST_ERROR` → `BAD_PIN`, `GATEWAY_ERROR` → `MALFORMED_BANK_ID`, etc.) |
| Configurable default UUIDs | `remitterBankId`, `beneficiaryBankId` |

When event is `payment.captured` with no `error_code` → no `declineCode` (maps to `MATCH_FOUND` → `SUCCESS`).  
When event is `payment.failed` → `declineCode` mapped from Razorpay's `error_code`.

#### [NEW] [PayUConnector.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/PayUConnector.java)

Stub/sandbox implementation. Accepts simplified mock payload:
```json
{ "txn_id": "...", "amount": "100.00", "status": "success|failed", "error": "..." }
```
Clearly commented as sandbox/stub — not a real PayU integration.

#### [NEW] [CashfreeConnector.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/CashfreeConnector.java)

Stub/sandbox implementation. Accepts simplified mock payload:
```json
{ "cf_order_id": "...", "order_amount": "100.00", "order_status": "PAID|FAILED", "error_message": "..." }
```
Clearly commented as sandbox/stub — not a real Cashfree integration.

---

### 4. REST Endpoints

#### [NEW] [MerchantController.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/api/MerchantController.java)

`POST /api/merchants/connect` — accepts `{ gateway, api_key, api_secret }`, encrypts credentials with `AesGcmEncryptor`, persists `Merchant`, returns `{ merchant_id, webhook_url }` where webhook_url is `/api/connectors/{gateway}/webhook?merchant_id={id}`.

#### [NEW] [ConnectorWebhookController.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/api/ConnectorWebhookController.java)

Three endpoints that receive raw gateway webhook calls, normalize them via the appropriate connector, then **reuse the existing ingestion pipeline** by calling `TransactionEventProducer.publishWebhookEvent()` and blocking on `WebhookResultHolder`:

- `POST /api/connectors/razorpay/webhook` → `RazorpayConnector.normalizeWebhookPayload()` → Kafka → consumer → transaction
- `POST /api/connectors/payu/webhook` → `PayUConnector.normalizeWebhookPayload()` → Kafka → consumer → transaction
- `POST /api/connectors/cashfree/webhook` → `CashfreeConnector.normalizeWebhookPayload()` → Kafka → consumer → transaction

Each follows the same pattern as [WebhookController.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/api/WebhookController.java): register future → publish to Kafka → await result → return response.

#### [NEW] [MerchantConnectRequest.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/api/MerchantConnectRequest.java)

Request DTO for the connect endpoint.

#### [NEW] [MerchantConnectResponse.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/java/com/upi/reconcile/connectors/api/MerchantConnectResponse.java)

Response DTO containing `merchant_id` and `webhook_url`.

---

### 5. Database Migrations (H2 test variant)

#### [MODIFY] [V1__init_schema.sql (test)](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/test/resources/db/migration-test/V1__init_schema.sql)

Add the `merchants` table in H2-compatible syntax to the existing test schema file.

---

### 6. Application Configuration

#### [MODIFY] [application.yml](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/main/resources/application.yml)

Add:
```yaml
app:
  encryption:
    aes-key: <Base64-encoded 256-bit key for dev>
  connectors:
    default-remitter-bank-id: <UUID of a seeded bank>
    default-beneficiary-bank-id: <UUID of a seeded bank>
```

#### [MODIFY] [application-test.yml](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/test/resources/application-test.yml)

Add the same `app.encryption.aes-key` and connector bank defaults for tests.

#### [MODIFY] [application-integration.yml](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/test/resources/application-integration.yml)

Add the same config for integration tests.

---

### 7. Integration Test

#### [NEW] [ConnectorPipelineIntegrationTest.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/test/java/com/upi/reconcile/connectors/ConnectorPipelineIntegrationTest.java)

Full integration test using Testcontainers (same pattern as existing [WebhookPipelineIntegrationTest.java](file:///Users/macbook/Development/JavaDevelopment/Hackathon/ui-c/src/test/java/com/upi/reconcile/ingestion/WebhookPipelineIntegrationTest.java)):

1. Seeds two banks
2. Sends a real-shaped Razorpay `payment.captured` test payload to `POST /api/connectors/razorpay/webhook`
3. Asserts a transaction record is created in `SUCCESS` state with `razorpay_payment_id` as the `idempotency_key`
4. Sends a `payment.failed` payload → asserts `BUSINESS_DECLINED` or `TECHNICAL_DECLINED` depending on error mapping
5. Tests the PayU/Cashfree stub endpoints with mock payloads → asserts transaction creation

---

## Design Decisions

> [!IMPORTANT]
> **No existing code is modified** — the connectors module purely adds new files. The existing `WebhookController`, `TransactionEventConsumer`, `StateMachine`, `TransactionProcessingService`, Kafka configs, and scheduler are untouched. The connector endpoints reuse the existing pipeline by calling `TransactionEventProducer.publishWebhookEvent()` directly.

> [!NOTE]
> **Bank ID mapping**: Razorpay webhooks don't carry UPI remitter/beneficiary bank IDs. The connector will use configurable default bank UUIDs (`app.connectors.default-remitter-bank-id` / `default-beneficiary-bank-id`) from application config. These should point to seeded bank entries. This is a reasonable trade-off for an MVP — real production would look up bank IDs from merchant config or VPA parsing.

> [!NOTE]
> **AES key management**: For dev/test a hardcoded Base64 key in `application.yml` is fine. Production would use environment variables or a secrets manager. The `AesGcmEncryptor` reads from `@Value("${app.encryption.aes-key}")`.

## Verification Plan

### Automated Tests
```bash
./gradlew test --tests "com.upi.reconcile.connectors.*"
```

### Manual Verification
- Start the app locally and `POST /api/merchants/connect` to get a webhook URL
- Send a Razorpay-shaped webhook to the returned URL and verify a transaction is created via `GET /api/transactions`
