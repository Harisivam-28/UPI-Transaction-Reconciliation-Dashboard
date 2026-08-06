package com.upi.reconcile.connectors.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for {@code POST /api/merchants/connect}.
 *
 * <pre>
 * {
 *   "merchant_id": "uuid",
 *   "webhook_url": "/api/connectors/razorpay/webhook?merchant_id=uuid",
 *   "webhook_secret": "hex-encoded-32-byte-random-secret"
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantConnectResponse {

    private UUID merchantId;
    private String webhookUrl;

    /**
     * One-time display: the HMAC-SHA256 signing secret the merchant must paste
     * into their gateway's webhook configuration. Not stored in plaintext
     * anywhere after this response — the merchant must save it now.
     */
    private String webhookSecret;
}
