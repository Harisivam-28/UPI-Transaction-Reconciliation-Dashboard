package com.upi.reconcile.connectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

/**
 * REST client for Razorpay's Payment and Refund APIs.
 *
 * <p>Uses Basic Auth ({@code key_id:key_secret}) as per Razorpay's authentication scheme.
 *
 * <h3>Endpoints used:</h3>
 * <ul>
 *   <li>{@code GET /v1/payments/:id} — Fetch payment status</li>
 *   <li>{@code POST /v1/payments/:id/refund} — Initiate full refund</li>
 * </ul>
 *
 * @see <a href="https://razorpay.com/docs/api/payments/#fetch-a-payment-with-id">Razorpay Docs</a>
 */
@Slf4j
@Component
public class RazorpayApiClient {

    private static final String BASE_URL = "https://api.razorpay.com";

    private final RestClient restClient;

    public RazorpayApiClient() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    /**
     * Result record for a payment status fetch.
     *
     * @param paymentId Razorpay payment ID (e.g. pay_XXXX)
     * @param status    One of: created, authorized, captured, refunded, failed
     */
    public record PaymentStatus(String paymentId, String status) {}

    /**
     * Result record for a refund initiation.
     *
     * @param refundId Razorpay refund ID (e.g. rfnd_XXXX)
     * @param status   Refund status (processed, pending, failed)
     */
    public record RefundResult(String refundId, String status) {}

    /**
     * Fetches the current status of a Razorpay payment.
     *
     * @param apiKey    Razorpay key_id (decrypted)
     * @param apiSecret Razorpay key_secret (decrypted)
     * @param paymentId Razorpay payment ID (e.g. pay_TMW2JQyu31KVFe)
     * @return the payment status
     */
    @SuppressWarnings("unchecked")
    public PaymentStatus fetchPaymentStatus(String apiKey, String apiSecret, String paymentId) {
        log.info("Fetching Razorpay payment status — payment_id={}", paymentId);

        Map<String, Object> response = restClient.get()
                .uri("/v1/payments/{id}", paymentId)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(apiKey, apiSecret))
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new RuntimeException("Razorpay returned null response for payment " + paymentId);
        }

        String status = (String) response.get("status");
        String id = (String) response.get("id");

        log.info("Razorpay payment status — payment_id={}, status={}", id, status);

        return new PaymentStatus(id, status);
    }

    /**
     * Initiates a full refund for a Razorpay payment.
     *
     * @param apiKey    Razorpay key_id (decrypted)
     * @param apiSecret Razorpay key_secret (decrypted)
     * @param paymentId Razorpay payment ID
     * @return the refund result
     */
    @SuppressWarnings("unchecked")
    public RefundResult initiateRefund(String apiKey, String apiSecret, String paymentId) {
        log.info("Initiating Razorpay refund — payment_id={}", paymentId);

        Map<String, Object> response = restClient.post()
                .uri("/v1/payments/{id}/refund", paymentId)
                .header(HttpHeaders.AUTHORIZATION, basicAuth(apiKey, apiSecret))
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new RuntimeException("Razorpay returned null response for refund on " + paymentId);
        }

        String refundId = (String) response.get("id");
        String status = (String) response.get("status");

        log.info("Razorpay refund initiated — refund_id={}, status={}", refundId, status);

        return new RefundResult(refundId, status);
    }

    /**
     * Builds a Basic Auth header value from key_id and key_secret.
     */
    private String basicAuth(String keyId, String keySecret) {
        String credentials = keyId + ":" + keySecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
