package com.upi.reconcile.scheduler;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Penalty calculation engine — ARCHITECTURE.md §4.
 * <p>
 * Formula:
 * <pre>
 *   penalty_days = max(0, floor((resolution_or_now - PENALTY_START) / SIMULATED_DAY_SECONDS) + 1)
 *   penalty_amount_inr = penalty_days * 100
 * </pre>
 * <p>
 * No penalty if resolved before {@code penaltyStart}.
 * Locks {@code penalty_amount_inr} permanently once state → {@code RESOLVED_REFUNDED}.
 * <p>
 * This class is intentionally a pure function — no DB, no Spring context —
 * so that the formula can be exhaustively unit-tested in isolation.
 */
@Component
public class PenaltyEngine {

    /** Per-day compensation amount in INR per RBI circular. */
    private static final BigDecimal DAILY_PENALTY_INR = new BigDecimal("100");

    /**
     * Computes the penalty amount in INR for a transaction.
     *
     * @param penaltyStart       the timestamp at which penalty begins accruing (T+2)
     * @param referenceTime      the current time or resolution time
     * @param simulatedDaySeconds how many real seconds represent one simulated day
     * @return the computed penalty in INR, or {@link BigDecimal#ZERO} if no penalty is due
     * @throws NullPointerException if {@code penaltyStart} or {@code referenceTime} is null
     */
    public BigDecimal calculate(OffsetDateTime penaltyStart,
                                OffsetDateTime referenceTime,
                                int simulatedDaySeconds) {
        if (penaltyStart == null || referenceTime == null) {
            throw new NullPointerException("penaltyStart and referenceTime must not be null");
        }

        long elapsedSeconds = ChronoUnit.SECONDS.between(penaltyStart, referenceTime);

        // No penalty if resolved before penalty clock starts
        if (elapsedSeconds < 0) {
            return BigDecimal.ZERO;
        }

        // penalty_days = floor(elapsed / simulatedDaySeconds) + 1
        long penaltyDays = Math.max(0, Math.floorDiv(elapsedSeconds, simulatedDaySeconds) + 1);

        return DAILY_PENALTY_INR.multiply(BigDecimal.valueOf(penaltyDays));
    }
}
