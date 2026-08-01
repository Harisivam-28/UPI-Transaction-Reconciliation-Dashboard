package com.upi.reconcile.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Externalised time-compression constants — ARCHITECTURE.md §3.
 * <p>
 * SIMULATED_DAY_SECONDS = 20        → 1 "day" = 20 real seconds
 * T_PLUS_1_DEADLINE     = txn.created_at + 1 * SIMULATED_DAY_SECONDS
 * PENALTY_START         = T_PLUS_1_DEADLINE + 1 * SIMULATED_DAY_SECONDS
 * ESCALATION_THRESHOLD  = T_PLUS_1_DEADLINE + 2 * SIMULATED_DAY_SECONDS
 * BATCH_TICK_INTERVAL   = SIMULATED_DAY_SECONDS / 3
 */
@Getter
@Configuration
public class TimeCompressionConfig {

    @Value("${reconciliation.simulated-day-seconds:20}")
    private int simulatedDaySeconds;

    @Value("${reconciliation.batch-tick-interval-seconds:7}")
    private int batchTickIntervalSeconds;

    @Value("${reconciliation.escalation-threshold-days:2}")
    private int escalationThresholdDays;

    /**
     * T+1 deadline offset in real seconds from transaction creation.
     */
    public int getTatDeadlineSeconds() {
        return simulatedDaySeconds;
    }

    /**
     * Penalty start offset in real seconds from transaction creation (T+2).
     */
    public int getPenaltyStartSeconds() {
        return simulatedDaySeconds * 2;
    }

    /**
     * Escalation threshold in real seconds from T+1 deadline.
     */
    public int getEscalationThresholdSeconds() {
        return simulatedDaySeconds * escalationThresholdDays;
    }
}
