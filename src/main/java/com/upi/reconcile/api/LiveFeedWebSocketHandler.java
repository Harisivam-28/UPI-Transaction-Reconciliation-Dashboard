package com.upi.reconcile.api;

import com.upi.reconcile.domain.TransactionStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Listens for {@link TransactionStateChangedEvent} (published by the batch
 * scheduler and processing service) and broadcasts the payload to all
 * STOMP subscribers on {@code /topic/live-feed}.
 * <p>
 * Payload shape per ARCHITECTURE.md §7:
 * <pre>
 *   { txn_id, old_state, new_state, penalty_amount_inr, bank_id, timestamp }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveFeedWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void onStateChange(TransactionStateChangedEvent event) {
        LiveFeedMessage message = new LiveFeedMessage(
                event.getTxnId(),
                event.getFromState() != null ? event.getFromState().name() : null,
                event.getToState().name(),
                event.getPenaltyAmountInr(),
                event.getRemitterBankId(),
                event.getTransitionedAt()
        );

        messagingTemplate.convertAndSend("/topic/live-feed", message);

        log.debug("Broadcast state change to /topic/live-feed: {} → {} (txn={})",
                message.oldState(), message.newState(), message.txnId());
    }

    /**
     * Immutable record for the live-feed JSON payload.
     */
    public record LiveFeedMessage(
            UUID txnId,
            String oldState,
            String newState,
            BigDecimal penaltyAmountInr,
            UUID bankId,
            OffsetDateTime timestamp
    ) {}
}
