package com.upi.reconcile.domain;

/**
 * Thrown when a {@link TransactionEvent} is applied to a {@link TransactionState}
 * that has no matching row in the ARCHITECTURE.md §2 transition table.
 *
 * <p>This prevents the system from silently allowing arbitrary state jumps —
 * every transition must be explicitly sanctioned by the specification.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final TransactionState fromState;
    private final TransactionEvent event;

    public IllegalStateTransitionException(TransactionState fromState, TransactionEvent event) {
        super(String.format(
                "No transition defined for state [%s] + event [%s]",
                fromState, event));
        this.fromState = fromState;
        this.event = event;
    }

    public TransactionState getFromState() {
        return fromState;
    }

    public TransactionEvent getEvent() {
        return event;
    }
}
