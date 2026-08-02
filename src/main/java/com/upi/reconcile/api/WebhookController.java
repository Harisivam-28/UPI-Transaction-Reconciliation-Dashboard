package com.upi.reconcile.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.ingestion.TransactionEventProducer;
import com.upi.reconcile.ingestion.WebhookResultHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * Webhook ingestion endpoint — ARCHITECTURE.md §7.
 *
 * <pre>
 * POST /api/webhooks/transaction
 *   body: { idempotency_key, remitter_bank_id, beneficiary_bank_id,
 *           amount_inr, order_reference, decline_code? }
 *   → 200 { txn_id, state }  |  200 { txn_id, state: "DUPLICATE_IGNORED" }
 * </pre>
 *
 * <p>The controller validates the request, publishes the payload to Kafka topic
 * {@code transaction-events} keyed by {@code idempotency_key}, then blocks on a
 * {@link CompletableFuture} that the consumer completes after processing.
 * This keeps all dedup + persistence logic in the Kafka consumer while
 * honouring the synchronous REST API contract.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final TransactionEventProducer producer;
    private final WebhookResultHolder resultHolder;
    private final ObjectMapper objectMapper;

    @PostMapping("/transaction")
    public ResponseEntity<WebhookResponse> receiveTransaction(
            @Valid @RequestBody WebhookRequest request) throws Exception {

        log.info("Received webhook — idempotency_key={}", request.getIdempotencyKey());

        // 1. Register a future BEFORE publishing to Kafka
        //    (avoids race where consumer completes before we register)
        CompletableFuture<WebhookResponse> future =
                resultHolder.register(request.getIdempotencyKey());

        // 2. Serialize and publish to Kafka
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            resultHolder.completeExceptionally(request.getIdempotencyKey(), e);
            throw new RuntimeException("Failed to serialize webhook request", e);
        }

        producer.publishWebhookEvent(request.getIdempotencyKey(), json);

        // 3. Block until the consumer processes the message
        WebhookResponse response = resultHolder.await(future);

        log.info("Webhook processed — txn={}, state={}",
                response.getTxnId(), response.getState());

        return ResponseEntity.ok(response);
    }
}
