package com.upi.reconcile.domain;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pure state-transition logic implementing ARCHITECTURE.md §2.
 *
 * <p>Takes a current {@link TransactionState} and an incoming {@link TransactionEvent},
 * looks up the next state from the pre-built transition table, and returns it.
 * If the (state, event) pair is not explicitly listed, throws
 * {@link IllegalStateTransitionException}.
 *
 * <p>This class is intentionally free of database, Kafka, or HTTP concerns —
 * it is a pure function wrapped in a Spring service for easy injection.
 *
 * <h3>Transition Table (ARCHITECTURE.md §2):</h3>
 * <pre>
 * INITIATED             + MATCH_FOUND              → SUCCESS
 * INITIATED             + BD_CODE                  → BUSINESS_DECLINED
 * INITIATED             + TD_CODE                  → TECHNICAL_DECLINED
 * INITIATED             + NO_CONFIRMATION           → DEEMED_APPROVED
 * TECHNICAL_DECLINED    + RETRY_SUCCESS             → SUCCESS
 * TECHNICAL_DECLINED    + RETRIES_EXHAUSTED         → PENDING_RECONCILIATION
 * DEEMED_APPROVED       + RETRIES_EXHAUSTED         → PENDING_RECONCILIATION
 * PENDING_RECONCILIATION + BATCH_RESOLVED           → AUTO_REVERSED
 * PENDING_RECONCILIATION + DEADLINE_PASSED          → TAT_BREACHED
 * TAT_BREACHED          + DEADLINE_PASSED           → PENALTY_ACCRUING
 * PENALTY_ACCRUING      + RESOLUTION_ARRIVED        → RESOLVED_REFUNDED
 * PENALTY_ACCRUING      + ESCALATION_THRESHOLD_HIT  → ESCALATED
 * </pre>
 */
@Service
public class StateMachine {

    /**
     * Immutable, nested-map transition table.
     * Outer key = current state, inner key = event, value = next state.
     */
    private static final Map<TransactionState, Map<TransactionEvent, TransactionState>> TRANSITIONS;

    static {
        var table = new EnumMap<TransactionState, Map<TransactionEvent, TransactionState>>(TransactionState.class);

        // INITIATED → 4 possible events
        var initiated = new EnumMap<TransactionEvent, TransactionState>(TransactionEvent.class);
        initiated.put(TransactionEvent.MATCH_FOUND,     TransactionState.SUCCESS);
        initiated.put(TransactionEvent.BD_CODE,         TransactionState.BUSINESS_DECLINED);
        initiated.put(TransactionEvent.TD_CODE,         TransactionState.TECHNICAL_DECLINED);
        initiated.put(TransactionEvent.NO_CONFIRMATION, TransactionState.DEEMED_APPROVED);
        table.put(TransactionState.INITIATED, Collections.unmodifiableMap(initiated));

        // TECHNICAL_DECLINED → 2 possible events
        var techDeclined = new EnumMap<TransactionEvent, TransactionState>(TransactionEvent.class);
        techDeclined.put(TransactionEvent.RETRY_SUCCESS,    TransactionState.SUCCESS);
        techDeclined.put(TransactionEvent.RETRIES_EXHAUSTED, TransactionState.PENDING_RECONCILIATION);
        table.put(TransactionState.TECHNICAL_DECLINED, Collections.unmodifiableMap(techDeclined));

        // DEEMED_APPROVED → enters queue (§2: "Enters queue → PENDING_RECONCILIATION")
        var deemedApproved = new EnumMap<TransactionEvent, TransactionState>(TransactionEvent.class);
        deemedApproved.put(TransactionEvent.RETRIES_EXHAUSTED, TransactionState.PENDING_RECONCILIATION);
        table.put(TransactionState.DEEMED_APPROVED, Collections.unmodifiableMap(deemedApproved));

        // PENDING_RECONCILIATION → 2 possible events
        var pendingRecon = new EnumMap<TransactionEvent, TransactionState>(TransactionEvent.class);
        pendingRecon.put(TransactionEvent.BATCH_RESOLVED,  TransactionState.AUTO_REVERSED);
        pendingRecon.put(TransactionEvent.DEADLINE_PASSED, TransactionState.TAT_BREACHED);
        table.put(TransactionState.PENDING_RECONCILIATION, Collections.unmodifiableMap(pendingRecon));

        // TAT_BREACHED → immediately transitions to PENALTY_ACCRUING
        var tatBreached = new EnumMap<TransactionEvent, TransactionState>(TransactionEvent.class);
        tatBreached.put(TransactionEvent.DEADLINE_PASSED, TransactionState.PENALTY_ACCRUING);
        table.put(TransactionState.TAT_BREACHED, Collections.unmodifiableMap(tatBreached));

        // PENALTY_ACCRUING → 2 possible events
        var penaltyAccruing = new EnumMap<TransactionEvent, TransactionState>(TransactionEvent.class);
        penaltyAccruing.put(TransactionEvent.RESOLUTION_ARRIVED,       TransactionState.RESOLVED_REFUNDED);
        penaltyAccruing.put(TransactionEvent.ESCALATION_THRESHOLD_HIT, TransactionState.ESCALATED);
        table.put(TransactionState.PENALTY_ACCRUING, Collections.unmodifiableMap(penaltyAccruing));

        TRANSITIONS = Collections.unmodifiableMap(table);
    }

    /**
     * Returns the next state for the given current state and incoming event.
     *
     * @param currentState the transaction's current state (must not be null)
     * @param event        the event to apply (must not be null)
     * @return the resulting state per the transition table
     * @throws IllegalStateTransitionException if the (state, event) pair is not
     *                                         in the transition table
     * @throws NullPointerException            if either argument is null
     */
    public TransactionState transition(TransactionState currentState, TransactionEvent event) {
        if (currentState == null) {
            throw new NullPointerException("currentState must not be null");
        }
        if (event == null) {
            throw new NullPointerException("event must not be null");
        }

        Map<TransactionEvent, TransactionState> eventMap = TRANSITIONS.get(currentState);
        if (eventMap == null) {
            throw new IllegalStateTransitionException(currentState, event);
        }

        TransactionState nextState = eventMap.get(event);
        if (nextState == null) {
            throw new IllegalStateTransitionException(currentState, event);
        }

        return nextState;
    }
}
