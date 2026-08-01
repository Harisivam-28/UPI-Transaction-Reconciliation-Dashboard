package com.upi.reconcile.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka producer wrapper for publishing state-transition events.
 * <p>
 * TODO: Implement typed event publishing for state transitions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventProducer {

    private static final String TOPIC = "txn-state-changes";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void publishStateChange(String txnId, String payload) {
        log.info("Publishing state change for txn {}", txnId);
        kafkaTemplate.send(TOPIC, txnId, payload);
    }
}
