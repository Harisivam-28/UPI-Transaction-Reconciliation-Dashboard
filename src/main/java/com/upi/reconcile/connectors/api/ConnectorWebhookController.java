package com.upi.reconcile.connectors.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookRequest;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.connectors.PaymentGatewayConnector;
import com.upi.reconcile.connectors.crypto.HmacSignatureVerifier;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.ingestion.TransactionEventProducer;
import com.upi.reconcile.ingestion.WebhookResultHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhook endpoints for payment gateway connectors.
 *
 * <p>Each endpoint receives raw webhook calls from the respective gateway,
 * normalizes them via the appropriate {@link PaymentGatewayConnector}, then
 * feeds the resulting {@link WebhookRequest} into the existing Kafka
 * ingestion pipeline — exactly the same flow as
 * {@link com.upi.reconcile.api.WebhookController}.
 *
 * <pre>
 * POST /api/connectors/razorpay/webhook?merchant_id=uuid   → RazorpayConnector (HMAC-SHA256 verified)
 * POST /api/connectors/payu/webhook                        → PayUConnector      (stub — no signature)
 * POST /api/connectors/cashfree/webhook                    → CashfreeConnector  (stub — no signature)
 * </pre>
 *
 * <p>Flow: receive raw payload → (verify signature for Razorpay) → normalize
 * → register future → publish to Kafka → consumer processes → return result.
 */
@Slf4j
@RestController
@RequestMapping("/api/connectors")
public class ConnectorWebhookController {

    private final Map<String, PaymentGatewayConnector> connectorMap;
    private final TransactionEventProducer producer;
    private final WebhookResultHolder resultHolder;
    private final ObjectMapper objectMapper;
    private final MerchantRepository merchantRepository;

    public ConnectorWebhookController(List<PaymentGatewayConnector> connectors,
                                       TransactionEventProducer producer,
                                       WebhookResultHolder resultHolder,
                                       ObjectMapper objectMapper,
                                       MerchantRepository merchantRepository) {
        this.connectorMap = connectors.stream()
                .collect(Collectors.toMap(PaymentGatewayConnector::gatewayName, Function.identity()));
        this.producer = producer;
        this.resultHolder = resultHolder;
        this.objectMapper = objectMapper;
        this.merchantRepository = merchantRepository;
    }

    /**
     * Razorpay webhook endpoint — validates HMAC-SHA256 signature before processing.
     *
     * <p>Razorpay signs each webhook POST body using the webhook secret configured in
     * the merchant's Razorpay Dashboard → Settings → Webhooks. The signature is sent
     * as a lowercase hex string in the {@code X-Razorpay-Signature} header.
     *
     * <p>We look up the merchant by the {@code merchant_id} query parameter (which is
     * embedded in the webhook URL returned during onboarding), retrieve their stored
     * {@code webhook_secret}, and verify the HMAC. Requests with missing or invalid
     * signatures are rejected with HTTP 401.
     *
     * @param rawBody   the raw request body as a string (needed for HMAC computation)
     * @param signature the X-Razorpay-Signature header value
     * @param merchantId the merchant_id query parameter from the webhook URL
     */
    @PostMapping("/razorpay/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<WebhookResponse> razorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestParam(value = "merchant_id", required = false) String merchantId) throws Exception {

        // ── 1. Validate signature header is present ─────────────────
        if (signature == null || signature.isBlank()) {
            log.warn("Razorpay webhook rejected — missing X-Razorpay-Signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ── 2. Look up merchant and their webhook secret ────────────
        if (merchantId == null || merchantId.isBlank()) {
            log.warn("Razorpay webhook rejected — missing merchant_id query parameter");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID merchantUuid;
        try {
            merchantUuid = UUID.fromString(merchantId);
        } catch (IllegalArgumentException e) {
            log.warn("Razorpay webhook rejected — invalid merchant_id format: {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Merchant> merchantOpt = merchantRepository.findById(merchantUuid);
        if (merchantOpt.isEmpty()) {
            log.warn("Razorpay webhook rejected — unknown merchant_id: {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Merchant merchant = merchantOpt.get();
        String webhookSecret = merchant.getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Razorpay webhook rejected — merchant {} has no webhook_secret configured", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ── 3. Verify HMAC-SHA256 signature ─────────────────────────
        if (!HmacSignatureVerifier.verify(rawBody, webhookSecret, signature)) {
            log.warn("Razorpay webhook rejected — HMAC-SHA256 signature mismatch for merchant {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Razorpay webhook signature verified for merchant {}", merchantId);

        // ── 4. Deserialize and process normally ─────────────────────
        Map<String, Object> rawPayload = objectMapper.readValue(rawBody, Map.class);
        return processGatewayWebhook("razorpay", rawPayload, merchantUuid);
    }

    /**
     * PayU stub webhook — no signature verification (sandbox integration).
     */
    @PostMapping("/payu/webhook")
    public ResponseEntity<WebhookResponse> payuWebhook(
            @RequestBody Map<String, Object> rawPayload,
            @RequestParam(value = "merchant_id", required = false) String merchantId) throws Exception {
        UUID merchantUuid = merchantId != null ? UUID.fromString(merchantId) : null;
        return processGatewayWebhook("payu", rawPayload, merchantUuid);
    }

    /**
     * Cashfree stub webhook — no signature verification (sandbox integration).
     */
    @PostMapping("/cashfree/webhook")
    public ResponseEntity<WebhookResponse> cashfreeWebhook(
            @RequestBody Map<String, Object> rawPayload,
            @RequestParam(value = "merchant_id", required = false) String merchantId) throws Exception {
        UUID merchantUuid = merchantId != null ? UUID.fromString(merchantId) : null;
        return processGatewayWebhook("cashfree", rawPayload, merchantUuid);
    }

    /**
     * Common processing logic for all gateway webhooks.
     *
     * <ol>
     *   <li>Look up the connector for the gateway</li>
     *   <li>Normalize the raw payload into a {@link WebhookRequest}</li>
     *   <li>Register a {@link CompletableFuture} on the result holder</li>
     *   <li>Publish to Kafka (same topic as the main webhook endpoint)</li>
     *   <li>Block until the consumer processes the message</li>
     * </ol>
     */
    private ResponseEntity<WebhookResponse> processGatewayWebhook(
            String gatewayName, Map<String, Object> rawPayload, UUID merchantId) throws Exception {

        PaymentGatewayConnector connector = connectorMap.get(gatewayName);
        if (connector == null) {
            log.error("No connector found for gateway '{}'", gatewayName);
            return ResponseEntity.badRequest().build();
        }

        log.info("Received {} webhook — normalizing payload", gatewayName);

        // 1. Normalize the raw payload into internal schema
        WebhookRequest normalized = connector.normalizeWebhookPayload(rawPayload);
        normalized.setSourceGateway(gatewayName);
        normalized.setMerchantId(merchantId);

        log.info("Normalized {} webhook — idempotency_key={}", gatewayName, normalized.getIdempotencyKey());

        // 2. Register a future BEFORE publishing to Kafka
        CompletableFuture<WebhookResponse> future =
                resultHolder.register(normalized.getIdempotencyKey());

        // 3. Serialize the normalized request and publish to Kafka
        String json;
        try {
            json = objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            resultHolder.completeExceptionally(normalized.getIdempotencyKey(), e);
            throw new RuntimeException("Failed to serialize normalized webhook request", e);
        }

        producer.publishWebhookEvent(normalized.getIdempotencyKey(), json);

        // 4. Block until the consumer processes the message
        WebhookResponse response = resultHolder.await(future);

        log.info("{} webhook processed — txn={}, state={}",
                gatewayName, response.getTxnId(), response.getState());

        return ResponseEntity.ok(response);
    }
}
