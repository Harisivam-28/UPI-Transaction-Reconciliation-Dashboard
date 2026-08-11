package com.upi.reconcile.connectors.api;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for {@code POST /api/merchants/connect}.
 *
 * <pre>
 * {
 *   "gateway":    "razorpay" | "payu" | "cashfree",
 *   "api_key":    "rzp_test_XXXX",
 *   "api_secret": "secret_XXXX"
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantConnectRequest {

    @NotBlank(message = "gateway is required")
    private String gateway;

    @NotBlank(message = "api_key is required")
    private String apiKey;

    @NotBlank(message = "api_secret is required")
    private String apiSecret;

    /** Optional merchant name — defaults to "Merchant-{uuid}" if absent. */
    private String name;
}
