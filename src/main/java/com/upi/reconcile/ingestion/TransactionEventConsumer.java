package com.upi.reconcile.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.config.IdempotencyService;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionProcessingService;
import com.upi.reconcile.domain.WebhookEvent;
import com.upi.reconcile.domain.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Kafka consumer for the {@code transaction-events} topic.
 *
 * <h3>Processing flow:</h3>
 * <ol>
 *   <li><b>Redis fast path</b> — check {@link IdempotencyService} (sub-ms)</li>
 *   <li><b>DB fallback</b> — query {@code webhook_events} table if Redis misses</li>
 *   <li><b>If duplicate</b> — log to {@code webhook_events} with {@code duplicate_of} set,
 *       emit {@code DUPLICATE_IGNORED}, do NOT create a new transaction</li>
 *   <li><b>If new</b> — persist {@code webhook_events} and {@code transactions}
 *       (state=INITIATED), feed into state machine for first transition</li>
 * </ol>
 *
 * <p><b>Manual ack</b>: offset is committed only after the DB write succeeds
 * (via {@link Acknowledgment#acknowledge()}). A consumer crash mid-processing
 * triggers Kafka redelivery → idempotency check prevents duplicate transactions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final IdempotencyService idempotencyService;
    private final WebhookEventRepository webhookEventRepository;
    private final TransactionProcessingService transactionProcessingService;
    private final WebhookResultHolder resultHolder;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = TransactionEventProducer.WEBHOOK_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(@Payload String message,
                        @Header(name = "kafka_receivedMessageKey", required = false) String key,
                        Acknowledgment acknowledgment) {

        String idempotencyKey = key;
        log.info("Consuming transaction event — key={}", idempotencyKey);

        try {
            JsonNode json = objectMapper.readTree(message);
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                idempotencyKey = json.path("idempotencyKey").asText();
            }

            // ── Step 1: Redis fast-path dedup ────────────────────────
            String cachedTxnId = idempotencyService.getExistingTxnId(idempotencyKey);
            if (cachedTxnId != null) {
                log.info("Duplicate detected via Redis — key={}, existing txn={}",
                        idempotencyKey, cachedTxnId);
                handleDuplicate(idempotencyKey, message, cachedTxnId);
                acknowledgment.acknowledge();
                return;
            }

            // ── Step 2: DB fallback dedup ─────────────────────────────
            Optional<WebhookEvent> existing = webhookEventRepository
                    .findFirstByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Duplicate detected via DB — key={}, existing webhook_event={}",
                        idempotencyKey, existing.get().getId());
                handleDuplicate(idempotencyKey, message,
                        findTxnIdByWebhookEvent(existing.get()));
                acknowledgment.acknowledge();
                return;
            }

            // ── Step 3: New event — process ──────────────────────────
            WebhookEvent webhookEvent = WebhookEvent.builder()
                    .idempotencyKey(idempotencyKey)
                    .payload(message)
                    .receivedAt(OffsetDateTime.now())
                    .build();
            webhookEventRepository.save(webhookEvent);

            // Extract fields from payload
            UUID remitterBankId = UUID.fromString(json.get("remitterBankId").asText());
            UUID beneficiaryBankId = UUID.fromString(json.get("beneficiaryBankId").asText());
            BigDecimal amountInr = new BigDecimal(json.get("amountInr").asText());
            String orderReference = json.get("orderReference").asText();
            String declineCode = json.has("declineCode") && !json.get("declineCode").isNull()
                    ? json.get("declineCode").asText()
                    : null;
            String sourceGateway = json.has("sourceGateway") && !json.get("sourceGateway").isNull()
                    ? json.get("sourceGateway").asText()
                    : null;
            UUID merchantId = json.has("merchantId") && !json.get("merchantId").isNull()
                    ? UUID.fromString(json.get("merchantId").asText())
                    : null;

            Transaction txn = transactionProcessingService.processNewTransaction(
                    idempotencyKey, remitterBankId, beneficiaryBankId,
                    amountInr, orderReference, declineCode, sourceGateway, merchantId);

            // Mark in Redis for fast-path on subsequent duplicates
            idempotencyService.markProcessed(idempotencyKey, txn.getTxnId().toString());

            // Complete the REST controller's future
            WebhookResponse response = WebhookResponse.builder()
                    .txnId(txn.getTxnId())
                    .state(txn.getState().name())
                    .build();
            resultHolder.complete(idempotencyKey, response);

            log.info("Processed new transaction — txn={}, state={}, key={}",
                    txn.getTxnId(), txn.getState(), idempotencyKey);

            // ── Manual ack — only after DB write succeeded ───────────
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Error processing transaction event — key={}", idempotencyKey, e);
            resultHolder.completeExceptionally(idempotencyKey, e);
            // Do NOT acknowledge — Kafka will redeliver
            throw new RuntimeException("Failed to process webhook event", e);
        }
    }

    /**
     * Handle a duplicate: log the duplicate webhook_event and complete the
     * controller's future with DUPLICATE_IGNORED.
     */
    private void handleDuplicate(String idempotencyKey, String payload, String txnIdStr) {
        // Find the original webhook_event to set duplicate_of
        Optional<WebhookEvent> original = webhookEventRepository
                .findFirstByIdempotencyKey(idempotencyKey);
        Long originalId = original.map(WebhookEvent::getId).orElse(null);

        WebhookEvent dupEvent = WebhookEvent.builder()
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .receivedAt(OffsetDateTime.now())
                .duplicateOf(originalId)
                .build();
        webhookEventRepository.save(dupEvent);

        UUID txnId = txnIdStr != null ? UUID.fromString(txnIdStr) : null;
        WebhookResponse response = WebhookResponse.builder()
                .txnId(txnId)
                .state(WebhookResponse.DUPLICATE_IGNORED)
                .build();
        resultHolder.complete(idempotencyKey, response);

        log.info("DUPLICATE_IGNORED — key={}, original_event={}, txn={}",
                idempotencyKey, originalId, txnIdStr);
    }

    /**
     * Best-effort extraction of txn_id from a webhook event's payload.
     */
    private String findTxnIdByWebhookEvent(WebhookEvent event) {
        // The original webhook_event doesn't store txn_id directly,
        // but we can look it up from the idempotency key in Redis
        // or return null (the response still works without it)
        String cached = idempotencyService.getExistingTxnId(event.getIdempotencyKey());
        return cached; // may be null if Redis TTL expired
    }
}
