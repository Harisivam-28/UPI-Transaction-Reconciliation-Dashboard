package com.upi.reconcile.scheduler;

import org.springframework.stereotype.Component;

/**
 * Penalty calculation engine — ARCHITECTURE.md §4.
 * <p>
 * Formula:
 * <pre>
 *   penalty_days = max(0, floor((resolution_or_now - PENALTY_START) / SIMULATED_DAY_SECONDS) + 1)
 *   penalty_amount_inr = penalty_days * 100
 * </pre>
 * <p>
 * No penalty if resolved at or before T+1.
 * Locks penalty_amount_inr permanently once state → RESOLVED_REFUNDED.
 * <p>
 * TODO: Implement penalty calculation logic.
 */
@Component
public class PenaltyEngine {

    // TODO: Implement penalty calculation per §4 formula
    // TODO: Lock penalty on RESOLVED_REFUNDED transition
}
