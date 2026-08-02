package com.upi.reconcile.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka producer for two distinct topics:
 * <ul>
 *   <li>{@code transaction-events} — raw webhook payloads keyed by idempotency_key
 *       (published by {@link com.upi.reconcile.api.WebhookController})</li>
 *   <li>{@code txn-state-changes} — state transition notifications
 *       (consumed by WebSocket layer / downstream services)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventProducer {

    /** Topic for incoming webhook events — consumed by TransactionEventConsumer. */
    public static final String WEBHOOK_TOPIC = "transaction-events";

    /** Topic for state-change notifications — consumed by WebSocket/downstream. */
    private static final String STATE_CHANGE_TOPIC = "txn-state-changes";

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Publish a raw webhook payload to the {@code transaction-events} topic,
     * keyed by {@code idempotency_key} for partition affinity.
     *
     * @param idempotencyKey the webhook idempotency key (used as Kafka message key)
     * @param jsonPayload    the serialised webhook request body
     */
    public void publishWebhookEvent(String idempotencyKey, String jsonPayload) {
        log.info("Publishing webhook event to Kafka — key={}", idempotencyKey);
        kafkaTemplate.send(WEBHOOK_TOPIC, idempotencyKey, jsonPayload);
    }

    /**
     * Publish a state-change notification for downstream consumers.
     *
     * @param txnId   the transaction ID
     * @param payload the serialised state-change event
     */
    public void publishStateChange(String txnId, String payload) {
        log.info("Publishing state change for txn {}", txnId);
        kafkaTemplate.send(STATE_CHANGE_TOPIC, txnId, payload);
    }
}
