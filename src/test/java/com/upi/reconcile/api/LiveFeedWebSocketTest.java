package com.upi.reconcile.api;

import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link LiveFeedWebSocketHandler}.
 * <p>
 * Verifies that {@link TransactionStateChangedEvent} triggers a STOMP message
 * to {@code /topic/live-feed} with the correct payload shape.
 */
class LiveFeedWebSocketTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final LiveFeedWebSocketHandler handler = new LiveFeedWebSocketHandler(messagingTemplate);

    private static final UUID TXN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BANK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BENEFICIARY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime TIMESTAMP =
            OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("Event listener sends STOMP message to /topic/live-feed with correct payload")
    void broadcastsStateChangeToStompTopic() {
        TransactionStateChangedEvent event = new TransactionStateChangedEvent(
                this,
                TXN_ID,
                TransactionState.PENDING_RECONCILIATION,
                TransactionState.TAT_BREACHED,
                new BigDecimal("200.00"),
                BANK_ID,
                BENEFICIARY_ID,
                TIMESTAMP
        );

        handler.onStateChange(event);

        ArgumentCaptor<LiveFeedWebSocketHandler.LiveFeedMessage> captor =
                ArgumentCaptor.forClass(LiveFeedWebSocketHandler.LiveFeedMessage.class);

        verify(messagingTemplate).convertAndSend(eq("/topic/live-feed"), captor.capture());

        LiveFeedWebSocketHandler.LiveFeedMessage message = captor.getValue();
        assertThat(message.txnId()).isEqualTo(TXN_ID);
        assertThat(message.oldState()).isEqualTo("PENDING_RECONCILIATION");
        assertThat(message.newState()).isEqualTo("TAT_BREACHED");
        assertThat(message.penaltyAmountInr()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(message.bankId()).isEqualTo(BANK_ID);
        assertThat(message.timestamp()).isEqualTo(TIMESTAMP);
    }

    @Test
    @DisplayName("Handles null fromState gracefully (initial INITIATED transition)")
    void handlesNullFromState() {
        TransactionStateChangedEvent event = new TransactionStateChangedEvent(
                this,
                TXN_ID,
                null,
                TransactionState.INITIATED,
                BigDecimal.ZERO,
                BANK_ID,
                BENEFICIARY_ID,
                TIMESTAMP
        );

        handler.onStateChange(event);

        ArgumentCaptor<LiveFeedWebSocketHandler.LiveFeedMessage> captor =
                ArgumentCaptor.forClass(LiveFeedWebSocketHandler.LiveFeedMessage.class);

        verify(messagingTemplate).convertAndSend(eq("/topic/live-feed"), captor.capture());

        LiveFeedWebSocketHandler.LiveFeedMessage message = captor.getValue();
        assertThat(message.oldState()).isNull();
        assertThat(message.newState()).isEqualTo("INITIATED");
    }

    @Test
    @DisplayName("Payload contains all required fields per ARCHITECTURE.md §7")
    void payloadContainsAllRequiredFields() {
        TransactionStateChangedEvent event = new TransactionStateChangedEvent(
                this,
                TXN_ID,
                TransactionState.PENALTY_ACCRUING,
                TransactionState.RESOLVED_REFUNDED,
                new BigDecimal("500.00"),
                BANK_ID,
                BENEFICIARY_ID,
                TIMESTAMP
        );

        handler.onStateChange(event);

        ArgumentCaptor<LiveFeedWebSocketHandler.LiveFeedMessage> captor =
                ArgumentCaptor.forClass(LiveFeedWebSocketHandler.LiveFeedMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/live-feed"), captor.capture());

        LiveFeedWebSocketHandler.LiveFeedMessage msg = captor.getValue();

        // All 6 required fields from §7: txn_id, old_state, new_state,
        // penalty_amount_inr, bank_id, timestamp
        assertThat(msg.txnId()).isNotNull();
        assertThat(msg.oldState()).isNotNull();
        assertThat(msg.newState()).isNotNull();
        assertThat(msg.penaltyAmountInr()).isNotNull();
        assertThat(msg.bankId()).isNotNull();
        assertThat(msg.timestamp()).isNotNull();
    }
}
