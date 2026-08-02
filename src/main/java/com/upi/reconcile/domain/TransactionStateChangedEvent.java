package com.upi.reconcile.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published on every transaction state change so downstream listeners
 * (e.g. WebSocket broadcast) can react without coupling to the scheduler.
 *
 * <p>Carries the full context needed for the {@code /ws/live-feed} payload
 * defined in ARCHITECTURE.md §7:
 * <pre>
 *   { txn_id, old_state, new_state, penalty_amount_inr, bank_id }
 * </pre>
 */
@Getter
public class TransactionStateChangedEvent extends ApplicationEvent {

    private final UUID txnId;
    private final TransactionState fromState;
    private final TransactionState toState;
    private final BigDecimal penaltyAmountInr;
    private final UUID remitterBankId;
    private final UUID beneficiaryBankId;
    private final OffsetDateTime transitionedAt;

    public TransactionStateChangedEvent(Object source,
                                        UUID txnId,
                                        TransactionState fromState,
                                        TransactionState toState,
                                        BigDecimal penaltyAmountInr,
                                        UUID remitterBankId,
                                        UUID beneficiaryBankId,
                                        OffsetDateTime transitionedAt) {
        super(source);
        this.txnId = txnId;
        this.fromState = fromState;
        this.toState = toState;
        this.penaltyAmountInr = penaltyAmountInr;
        this.remitterBankId = remitterBankId;
        this.beneficiaryBankId = beneficiaryBankId;
        this.transitionedAt = transitionedAt;
    }
}
