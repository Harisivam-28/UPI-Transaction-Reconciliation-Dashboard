package com.upi.reconcile.domain;

/**
 * Events that drive the {@link TransactionState} machine
 * as defined in ARCHITECTURE.md §2 (State Transition Table).
 *
 * <p>Each enum constant maps to exactly one "Event / Condition" column
 * entry in the transition table.
 */
public enum TransactionEvent {

    /** Match found — amounts/IDs align (INITIATED → SUCCESS). */
    MATCH_FOUND,

    /** Decline code falls in BD set: bad PIN, invalid beneficiary, limit exceeded (INITIATED → BUSINESS_DECLINED). */
    BD_CODE,

    /** Decline code falls in TD set: malformed bank ID, missing exception code (INITIATED → TECHNICAL_DECLINED). */
    TD_CODE,

    /** No online credit confirmation from beneficiary bank within X seconds (INITIATED → DEEMED_APPROVED). */
    NO_CONFIRMATION,

    /** Retry succeeds within ≤2 auto-retries (TECHNICAL_DECLINED → SUCCESS). */
    RETRY_SUCCESS,

    /** All retries exhausted (TECHNICAL_DECLINED → PENDING_RECONCILIATION). */
    RETRIES_EXHAUSTED,

    /** Resolved at a simulated batch window before T+1 deadline (PENDING_RECONCILIATION → AUTO_REVERSED). */
    BATCH_RESOLVED,

    /** T+1 deadline passes unresolved (PENDING_RECONCILIATION → TAT_BREACHED). */
    DEADLINE_PASSED,

    /** Resolution event arrives while penalty is accruing (PENALTY_ACCRUING → RESOLVED_REFUNDED). */
    RESOLUTION_ARRIVED,

    /** Exceeds escalation threshold with no resolution (PENALTY_ACCRUING → ESCALATED). */
    ESCALATION_THRESHOLD_HIT,

    /** Gateway API confirms payment actually succeeded (PENALTY_ACCRUING → SUCCESS). */
    GATEWAY_STATUS_CHECK_SUCCESS,

    /** Gateway refund API call succeeded (PENALTY_ACCRUING → RESOLVED_REFUNDED). */
    GATEWAY_REFUND_COMPLETED;
}
