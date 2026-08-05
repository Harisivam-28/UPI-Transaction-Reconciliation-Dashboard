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
 *   "webhook_url": "/api/connectors/razorpay/webhook?merchant_id=uuid"
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
}
