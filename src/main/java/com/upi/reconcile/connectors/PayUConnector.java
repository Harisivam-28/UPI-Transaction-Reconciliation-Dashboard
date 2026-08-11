package com.upi.reconcile.connectors;

import com.upi.reconcile.api.WebhookRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * ⚠️ SANDBOX / STUB IMPLEMENTATION — NOT A REAL PayU INTEGRATION.
 *
 * <p>This connector accepts a simplified mock payload shape for development
 * and testing purposes. It does NOT implement PayU's actual webhook format,
 * authentication, or signature verification.
 *
 * <p>Expected mock payload shape:
 * <pre>
 * {
 *   "txn_id":  "PAYU-TXN-12345",
 *   "amount":  "100.00",
 *   "status":  "success" | "failed",
 *   "error":   "optional error description"
 * }
 * </pre>
 *
 * <p>TODO: Replace with real PayU webhook integration when ready.
 * See PayU docs: https://devguide.payu.in/api/webhooks/
 */
@Slf4j
@Component
public class PayUConnector implements PaymentGatewayConnector {

    private final UUID defaultRemitterBankId;
    private final UUID defaultBeneficiaryBankId;

    public PayUConnector(
            @Value("${app.connectors.default-remitter-bank-id}") String remitterBankId,
            @Value("${app.connectors.default-beneficiary-bank-id}") String beneficiaryBankId) {
        this.defaultRemitterBankId = UUID.fromString(remitterBankId);
        this.defaultBeneficiaryBankId = UUID.fromString(beneficiaryBankId);
    }

    @Override
    public String gatewayName() {
        return "payu";
    }

    /**
     * ⚠️ STUB: Normalizes a simplified mock PayU payload.
     * This is NOT a real PayU webhook — see class Javadoc.
     */
    @Override
    public WebhookRequest normalizeWebhookPayload(Map<String, Object> rawPayload) {
        // ── Extract fields from simplified mock payload ──────────────
        String txnId = (String) rawPayload.get("txn_id");
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException("Missing 'txn_id' in PayU stub payload");
        }

        String amountStr = (String) rawPayload.get("amount");
        if (amountStr == null || amountStr.isBlank()) {
            throw new IllegalArgumentException("Missing 'amount' in PayU stub payload");
        }
        BigDecimal amountInr = new BigDecimal(amountStr);

        String status = (String) rawPayload.get("status");
        String error = (String) rawPayload.get("error");

        // ── Map status to decline code ──────────────────────────────
        // STUB: "success" → no decline, "failed" → use error or default TD code
        String declineCode = null;
        if ("failed".equalsIgnoreCase(status)) {
            declineCode = (error != null && !error.isBlank()) ? error : "MISSING_EXCEPTION_CODE";
        }

        log.info("[STUB] PayU webhook normalized — txn_id={}, amount={}, status={}, decline={}",
                txnId, amountInr, status, declineCode);

        return WebhookRequest.builder()
                .idempotencyKey("payu-" + txnId)
                .remitterBankId(defaultRemitterBankId)
                .beneficiaryBankId(defaultBeneficiaryBankId)
                .amountInr(amountInr)
                .orderReference(txnId)
                .declineCode(declineCode)
                .build();
    }
}
