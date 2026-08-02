package com.upi.reconcile.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published when the rolling anomaly monitor detects a systemic failure
 * spike for a specific bank — ARCHITECTURE.md §5.
 *
 * <p>This is <em>not</em> a per-transaction state; it is a cross-transaction
 * signal that "this bank's rails are down right now", mirroring real
 * NPCI-acknowledged outage events.
 *
 * <p>Downstream listeners (e.g. WebSocket layer) consume this to surface
 * a frontend banner: "Possible outage at {bankName} — {affectedCount}
 * transactions affected".
 */
@Getter
public class SystemicAnomalyEvent extends ApplicationEvent {

    private final UUID bankId;
    private final String bankName;
    private final double failureRateNow;
    private final double historicalBaseline;
    private final long affectedTransactionCount;
    private final OffsetDateTime detectedAt;

    public SystemicAnomalyEvent(Object source,
                                UUID bankId,
                                String bankName,
                                double failureRateNow,
                                double historicalBaseline,
                                long affectedTransactionCount,
                                OffsetDateTime detectedAt) {
        super(source);
        this.bankId = bankId;
        this.bankName = bankName;
        this.failureRateNow = failureRateNow;
        this.historicalBaseline = historicalBaseline;
        this.affectedTransactionCount = affectedTransactionCount;
        this.detectedAt = detectedAt;
    }
}
