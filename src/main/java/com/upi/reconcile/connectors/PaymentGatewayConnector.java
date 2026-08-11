package com.upi.reconcile.connectors;

import com.upi.reconcile.api.WebhookRequest;

import java.util.Map;

/**
 * Contract for payment gateway connectors.
 *
 * <p>Each connector normalizes a gateway-specific raw webhook payload into the
 * internal {@link WebhookRequest} schema used by the existing
 * {@code POST /api/webhooks/transaction} ingestion pipeline.
 *
 * <p>Implementations must map the gateway's fields (payment ID, amount, status,
 * error codes) into the internal schema's fields (idempotency_key, amount_inr,
 * remitter_bank_id, beneficiary_bank_id, order_reference, decline_code).
 */
public interface PaymentGatewayConnector {

    /**
     * Normalize a raw gateway webhook payload into the internal transaction
     * event schema ({@link WebhookRequest}).
     *
     * @param rawPayload the deserialized JSON payload from the gateway webhook
     * @return a {@link WebhookRequest} ready to be fed into the Kafka ingestion pipeline
     * @throws IllegalArgumentException if the payload is missing required fields
     */
    WebhookRequest normalizeWebhookPayload(Map<String, Object> rawPayload);

    /**
     * Returns the gateway name this connector handles (e.g. "razorpay", "payu", "cashfree").
     *
     * @return lowercase gateway identifier
     */
    String gatewayName();
}
