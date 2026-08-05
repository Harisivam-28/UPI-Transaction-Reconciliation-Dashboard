package com.upi.reconcile.connectors.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookRequest;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.connectors.PaymentGatewayConnector;
import com.upi.reconcile.ingestion.TransactionEventProducer;
import com.upi.reconcile.ingestion.WebhookResultHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
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
 * POST /api/connectors/razorpay/webhook   → RazorpayConnector
 * POST /api/connectors/payu/webhook       → PayUConnector
 * POST /api/connectors/cashfree/webhook   → CashfreeConnector
 * </pre>
 *
 * <p>Flow: receive raw payload → normalize → register future → publish to Kafka
 * → consumer processes → return result.
 */
@Slf4j
@RestController
@RequestMapping("/api/connectors")
public class ConnectorWebhookController {

    private final Map<String, PaymentGatewayConnector> connectorMap;
    private final TransactionEventProducer producer;
    private final WebhookResultHolder resultHolder;
    private final ObjectMapper objectMapper;

    public ConnectorWebhookController(List<PaymentGatewayConnector> connectors,
                                       TransactionEventProducer producer,
                                       WebhookResultHolder resultHolder,
                                       ObjectMapper objectMapper) {
        this.connectorMap = connectors.stream()
                .collect(Collectors.toMap(PaymentGatewayConnector::gatewayName, Function.identity()));
        this.producer = producer;
        this.resultHolder = resultHolder;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay/webhook")
    public ResponseEntity<WebhookResponse> razorpayWebhook(
            @RequestBody Map<String, Object> rawPayload) throws Exception {
        return processGatewayWebhook("razorpay", rawPayload);
    }

    @PostMapping("/payu/webhook")
    public ResponseEntity<WebhookResponse> payuWebhook(
            @RequestBody Map<String, Object> rawPayload) throws Exception {
        return processGatewayWebhook("payu", rawPayload);
    }

    @PostMapping("/cashfree/webhook")
    public ResponseEntity<WebhookResponse> cashfreeWebhook(
            @RequestBody Map<String, Object> rawPayload) throws Exception {
        return processGatewayWebhook("cashfree", rawPayload);
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
            String gatewayName, Map<String, Object> rawPayload) throws Exception {

        PaymentGatewayConnector connector = connectorMap.get(gatewayName);
        if (connector == null) {
            log.error("No connector found for gateway '{}'", gatewayName);
            return ResponseEntity.badRequest().build();
        }

        log.info("Received {} webhook — normalizing payload", gatewayName);

        // 1. Normalize the raw payload into internal schema
        WebhookRequest normalized = connector.normalizeWebhookPayload(rawPayload);

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
