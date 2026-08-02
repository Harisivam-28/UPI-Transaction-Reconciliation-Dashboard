package com.upi.reconcile.api;

import com.upi.reconcile.domain.SystemicAnomalyEvent;
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
 * Also listens for {@link SystemicAnomalyEvent} (published by the anomaly
 * monitor) and broadcasts an {@code ANOMALY_FLAGGED} message on the same
 * topic, enabling the frontend to render a distinct banner.
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
     * Broadcasts systemic anomaly events on the same WebSocket topic
     * with a distinct {@code eventType} so the frontend can show a
     * banner separate from individual transaction updates.
     */
    @EventListener
    public void onSystemicAnomaly(SystemicAnomalyEvent event) {
        AnomalyMessage message = new AnomalyMessage(
                "ANOMALY_FLAGGED",
                event.getBankId(),
                event.getBankName(),
                event.getFailureRateNow(),
                event.getHistoricalBaseline(),
                event.getAffectedTransactionCount(),
                event.getDetectedAt()
        );

        messagingTemplate.convertAndSend("/topic/live-feed", message);

        log.warn("Broadcast ANOMALY_FLAGGED to /topic/live-feed: {} — {} txns affected",
                event.getBankName(), event.getAffectedTransactionCount());
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

    /**
     * Immutable record for the anomaly-flagged JSON payload (§5).
     */
    public record AnomalyMessage(
            String eventType,
            UUID bankId,
            String bankName,
            double failureRateNow,
            double historicalBaseline,
            long affectedCount,
            OffsetDateTime timestamp
    ) {}
}

