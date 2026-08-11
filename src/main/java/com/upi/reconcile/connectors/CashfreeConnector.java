package com.upi.reconcile.connectors;

import com.upi.reconcile.api.WebhookRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * ⚠️ SANDBOX / STUB IMPLEMENTATION — NOT A REAL CASHFREE INTEGRATION.
 *
 * <p>This connector accepts a simplified mock payload shape for development
 * and testing purposes. It does NOT implement Cashfree's actual webhook format,
 * authentication, or signature verification.
 *
 * <p>Expected mock payload shape:
 * <pre>
 * {
 *   "cf_order_id":    "CF-ORDER-12345",
 *   "order_amount":   "250.00",
 *   "order_status":   "PAID" | "FAILED",
 *   "error_message":  "optional error description"
 * }
 * </pre>
 *
 * <p>TODO: Replace with real Cashfree webhook integration when ready.
 * See Cashfree docs: https://docs.cashfree.com/docs/webhooks
 */
@Slf4j
@Component
public class CashfreeConnector implements PaymentGatewayConnector {

    private final UUID defaultRemitterBankId;
    private final UUID defaultBeneficiaryBankId;

    public CashfreeConnector(
            @Value("${app.connectors.default-remitter-bank-id}") String remitterBankId,
            @Value("${app.connectors.default-beneficiary-bank-id}") String beneficiaryBankId) {
        this.defaultRemitterBankId = UUID.fromString(remitterBankId);
        this.defaultBeneficiaryBankId = UUID.fromString(beneficiaryBankId);
    }

    @Override
    public String gatewayName() {
        return "cashfree";
    }

    /**
     * ⚠️ STUB: Normalizes a simplified mock Cashfree payload.
     * This is NOT a real Cashfree webhook — see class Javadoc.
     */
    @Override
    public WebhookRequest normalizeWebhookPayload(Map<String, Object> rawPayload) {
        // ── Extract fields from simplified mock payload ──────────────
        String cfOrderId = (String) rawPayload.get("cf_order_id");
        if (cfOrderId == null || cfOrderId.isBlank()) {
            throw new IllegalArgumentException("Missing 'cf_order_id' in Cashfree stub payload");
        }

        String amountStr = (String) rawPayload.get("order_amount");
        if (amountStr == null || amountStr.isBlank()) {
            throw new IllegalArgumentException("Missing 'order_amount' in Cashfree stub payload");
        }
        BigDecimal amountInr = new BigDecimal(amountStr);

        String orderStatus = (String) rawPayload.get("order_status");
        String errorMessage = (String) rawPayload.get("error_message");

        // ── Map status to decline code ──────────────────────────────
        // STUB: "PAID" → no decline, "FAILED" → use error_message or default TD code
        String declineCode = null;
        if ("FAILED".equalsIgnoreCase(orderStatus)) {
            declineCode = (errorMessage != null && !errorMessage.isBlank())
                    ? errorMessage
                    : "MISSING_EXCEPTION_CODE";
        }

        log.info("[STUB] Cashfree webhook normalized — cf_order_id={}, amount={}, status={}, decline={}",
                cfOrderId, amountInr, orderStatus, declineCode);

        return WebhookRequest.builder()
                .idempotencyKey("cashfree-" + cfOrderId)
                .remitterBankId(defaultRemitterBankId)
                .beneficiaryBankId(defaultBeneficiaryBankId)
                .amountInr(amountInr)
                .orderReference(cfOrderId)
                .declineCode(declineCode)
                .build();
    }
}
