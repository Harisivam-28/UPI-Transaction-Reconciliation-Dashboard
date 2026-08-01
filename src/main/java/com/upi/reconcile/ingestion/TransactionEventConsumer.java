package com.upi.reconcile.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for incoming transaction events.
 * Listens on the {@code txn-events} topic.
 * <p>
 * TODO: Implement message deserialization and hand-off to state machine.
 */
@Slf4j
@Component
public class TransactionEventConsumer {

    @KafkaListener(topics = "txn-events", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        log.info("Received transaction event: {}", message);
        // TODO: Deserialize message and process through state machine
    }
}
