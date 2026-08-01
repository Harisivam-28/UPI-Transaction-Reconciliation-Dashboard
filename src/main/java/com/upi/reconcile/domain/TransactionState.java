package com.upi.reconcile.domain;

/**
 * All transaction states defined in ARCHITECTURE.md §1.
 * <p>
 * Terminal states: SUCCESS, BUSINESS_DECLINED, AUTO_REVERSED, RESOLVED_REFUNDED, ESCALATED.
 * Non-terminal states drive the scheduler and penalty engine.
 */
public enum TransactionState {

    INITIATED,
    SUCCESS,
    BUSINESS_DECLINED,
    TECHNICAL_DECLINED,
    DEEMED_APPROVED,
    PENDING_RECONCILIATION,
    AUTO_REVERSED,
    TAT_BREACHED,
    PENALTY_ACCRUING,
    RESOLVED_REFUNDED,
    ESCALATED;

    /**
     * Returns true if this state is terminal (no further transitions expected).
     */
    public boolean isTerminal() {
        return this == SUCCESS
                || this == BUSINESS_DECLINED
                || this == AUTO_REVERSED
                || this == RESOLVED_REFUNDED
                || this == ESCALATED;
    }
}
