package com.upi.reconcile.connectors;

import com.upi.reconcile.api.WebhookRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay webhook connector — maps real Razorpay test-mode webhook payloads
 * into the internal {@link WebhookRequest} schema.
 *
 * <h3>Supported Razorpay events:</h3>
 * <ul>
 *   <li>{@code payment.captured} — successful payment capture</li>
 *   <li>{@code payment.failed} — payment failure with error code</li>
 * </ul>
 *
 * <h3>Field mapping:</h3>
 * <pre>
 * Razorpay field                              → Internal field
 * ─────────────────────────────────────────────────────────────
 * payload.payment.entity.id                   → idempotencyKey
 * payload.payment.entity.amount (paise)       → amountInr (÷ 100)
 * payload.payment.entity.order_id             → orderReference
 * payload.payment.entity.error_code           → declineCode (mapped)
 * configured default UUIDs                    → remitterBankId, beneficiaryBankId
 * </pre>
 *
 * <h3>Error code mapping (Razorpay → internal decline codes):</h3>
 * <pre>
 * BAD_REQUEST_ERROR     → BAD_PIN              (business decline)
 * GATEWAY_ERROR         → MALFORMED_BANK_ID    (technical decline)
 * SERVER_ERROR          → MALFORMED_BANK_ID    (technical decline)
 * (other/unknown)       → error_code as-is     (consumer treats unknown as TD)
 * </pre>
 *
 * <p>Razorpay test-mode webhook payload structure:
 * <pre>
 * {
 *   "event": "payment.captured",
 *   "payload": {
 *     "payment": {
 *       "entity": {
 *         "id": "pay_XXXXXXXXXXXX",
 *         "amount": 50000,           // in paise
 *         "currency": "INR",
 *         "status": "captured",
 *         "order_id": "order_XXXX",
 *         "error_code": null,
 *         "error_description": null
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
@Slf4j
@Component
public class RazorpayConnector implements PaymentGatewayConnector {

    private final UUID defaultRemitterBankId;
    private final UUID defaultBeneficiaryBankId;

    public RazorpayConnector(
            @Value("${app.connectors.default-remitter-bank-id}") String remitterBankId,
            @Value("${app.connectors.default-beneficiary-bank-id}") String beneficiaryBankId) {
        this.defaultRemitterBankId = UUID.fromString(remitterBankId);
        this.defaultBeneficiaryBankId = UUID.fromString(beneficiaryBankId);
    }

    @Override
    public String gatewayName() {
        return "razorpay";
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookRequest normalizeWebhookPayload(Map<String, Object> rawPayload) {
        String event = (String) rawPayload.get("event");
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Missing 'event' field in Razorpay webhook payload");
        }

        // Navigate: payload -> payment -> entity
        Map<String, Object> payload = (Map<String, Object>) rawPayload.get("payload");
        if (payload == null) {
            throw new IllegalArgumentException("Missing 'payload' field in Razorpay webhook");
        }
        Map<String, Object> payment = (Map<String, Object>) payload.get("payment");
        if (payment == null) {
            throw new IllegalArgumentException("Missing 'payload.payment' field in Razorpay webhook");
        }
        Map<String, Object> entity = (Map<String, Object>) payment.get("entity");
        if (entity == null) {
            throw new IllegalArgumentException("Missing 'payload.payment.entity' field in Razorpay webhook");
        }

        // Extract razorpay_payment_id → idempotency_key
        String razorpayPaymentId = (String) entity.get("id");
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
            throw new IllegalArgumentException("Missing 'payload.payment.entity.id' (razorpay_payment_id)");
        }

        // Extract amount in paise → convert to INR
        Object amountObj = entity.get("amount");
        BigDecimal amountInr = convertPaiseToInr(amountObj);

        // Extract order_id → orderReference
        String orderId = (String) entity.get("order_id");
        if (orderId == null || orderId.isBlank()) {
            orderId = "rzp-" + razorpayPaymentId; // fallback
        }

        // Map decline code based on event type and error_code
        String declineCode = mapDeclineCode(event, entity);

        log.info("Razorpay webhook normalized — event={}, payment_id={}, amount_inr={}, decline={}",
                event, razorpayPaymentId, amountInr, declineCode);

        return WebhookRequest.builder()
                .idempotencyKey(razorpayPaymentId)
                .remitterBankId(defaultRemitterBankId)
                .beneficiaryBankId(defaultBeneficiaryBankId)
                .amountInr(amountInr)
                .orderReference(orderId)
                .declineCode(declineCode)
                .build();
    }

    /**
     * Converts Razorpay amount (in paise, integer) to INR (BigDecimal with 2 decimal places).
     */
    private BigDecimal convertPaiseToInr(Object amountObj) {
        if (amountObj == null) {
            throw new IllegalArgumentException("Missing 'amount' in Razorpay payment entity");
        }
        long paise;
        if (amountObj instanceof Number) {
            paise = ((Number) amountObj).longValue();
        } else {
            paise = Long.parseLong(amountObj.toString());
        }
        return BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Maps Razorpay event type + error_code to internal decline codes.
     *
     * <ul>
     *   <li>{@code payment.captured} with no error → null (MATCH_FOUND → SUCCESS)</li>
     *   <li>{@code payment.failed} + BAD_REQUEST_ERROR → BAD_PIN (BD)</li>
     *   <li>{@code payment.failed} + GATEWAY_ERROR → MALFORMED_BANK_ID (TD)</li>
     *   <li>{@code payment.failed} + SERVER_ERROR → MALFORMED_BANK_ID (TD)</li>
     *   <li>{@code payment.failed} + other → passed through as-is (consumer treats unknown as TD)</li>
     * </ul>
     */
    private String mapDeclineCode(String event, Map<String, Object> entity) {
        if ("payment.captured".equals(event)) {
            // Successful capture — no decline
            return null;
        }

        if ("payment.failed".equals(event)) {
            String errorCode = (String) entity.get("error_code");
            if (errorCode == null || errorCode.isBlank()) {
                // Failed without error code — treat as technical decline
                return "MISSING_EXCEPTION_CODE";
            }
            return switch (errorCode) {
                case "BAD_REQUEST_ERROR" -> "BAD_PIN";
                case "GATEWAY_ERROR" -> "MALFORMED_BANK_ID";
                case "SERVER_ERROR" -> "MALFORMED_BANK_ID";
                default -> {
                    log.warn("Unknown Razorpay error_code '{}' — passing through as-is", errorCode);
                    yield errorCode;
                }
            };
        }

        // Unknown event type — log warning and treat as no decline
        log.warn("Unsupported Razorpay event '{}' — treating as captured (no decline)", event);
        return null;
    }
}
