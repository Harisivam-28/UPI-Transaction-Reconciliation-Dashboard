package com.upi.reconcile.scheduler;

import com.upi.reconcile.config.TimeCompressionConfig;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.SystemicAnomalyEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Rolling anomaly monitor — ARCHITECTURE.md §5.
 * <p>
 * Runs on every {@code BATCH_TICK_INTERVAL} tick (same cadence as the batch
 * resolution scheduler) and computes each bank's failure rate over a
 * configurable rolling window ({@code anomaly-window-seconds}, default 60s).
 * <p>
 * If any bank's live failure rate exceeds 3× its historical baseline
 * ({@code historical_td_rate + historical_deemed_approved_rate}), a
 * {@link SystemicAnomalyEvent} is published. The WebSocket layer picks
 * this up and broadcasts an {@code ANOMALY_FLAGGED} message so the
 * frontend can show a banner: "Possible outage at {bank} — {N} txns affected".
 * <p>
 * This distinguishes "one customer's bad luck" from "this bank's rails
 * are down right now" — mirrors real NPCI-acknowledged outage events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyMonitor {

    /** The anomaly multiplier: flag when live rate > 3× historical baseline. */
    static final double ANOMALY_MULTIPLIER = 3.0;

    /** States that count as "failures" for anomaly detection. */
    private static final List<TransactionState> FAILURE_STATES = List.of(
            TransactionState.TECHNICAL_DECLINED,
            TransactionState.DEEMED_APPROVED
    );

    private final TransactionRepository transactionRepository;
    private final BankRepository bankRepository;
    private final TimeCompressionConfig timeConfig;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Scheduled at the same cadence as the batch resolution tick.
     * On each tick, sweeps all banks and checks for systemic anomalies.
     */
    @Scheduled(fixedDelayString = "${reconciliation.batch-tick-interval-seconds:7}000")
    public void checkForAnomalies() {
        OffsetDateTime now = OffsetDateTime.now();
        sweepAnomalies(now);
    }

    /**
     * Core anomaly detection logic — extracted for testability.
     *
     * @param now the reference timestamp for the rolling window cutoff
     */
    void sweepAnomalies(OffsetDateTime now) {
        OffsetDateTime windowCutoff = now.minusSeconds(timeConfig.getAnomalyWindowSeconds());
        List<Bank> banks = bankRepository.findAll();

        for (Bank bank : banks) {
            try {
                evaluateBank(bank, windowCutoff, now);
            } catch (Exception e) {
                log.error("Anomaly check failed for bank {}: {}",
                        bank.getName(), e.getMessage());
            }
        }
    }

    /**
     * Evaluates a single bank for anomaly conditions.
     */
    private void evaluateBank(Bank bank, OffsetDateTime windowCutoff, OffsetDateTime now) {
        long totalCount = transactionRepository
                .countByRemitterBank_BankIdAndCreatedAtAfter(bank.getBankId(), windowCutoff);

        // Skip banks with no transactions in the window — avoid division by zero
        if (totalCount == 0) {
            return;
        }

        long failureCount = transactionRepository
                .countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                        bank.getBankId(), FAILURE_STATES, windowCutoff);

        double failureRateNow = (double) failureCount / totalCount;
        double historicalBaseline = computeHistoricalBaseline(bank);

        if (historicalBaseline <= 0) {
            // No historical data — cannot meaningfully compare; skip
            log.debug("Bank {} has no historical baseline — skipping anomaly check",
                    bank.getName());
            return;
        }

        double threshold = historicalBaseline * ANOMALY_MULTIPLIER;

        if (failureRateNow > threshold) {
            log.warn("🚨 SYSTEMIC ANOMALY: {} — failure rate {:.4f} > {:.4f} threshold "
                            + "({} failures / {} total in window)",
                    bank.getName(), failureRateNow, threshold, failureCount, totalCount);

            eventPublisher.publishEvent(new SystemicAnomalyEvent(
                    this,
                    bank.getBankId(),
                    bank.getName(),
                    failureRateNow,
                    historicalBaseline,
                    failureCount,
                    now
            ));
        } else {
            log.debug("Bank {} OK — failure rate {:.4f} <= {:.4f} threshold",
                    bank.getName(), failureRateNow, threshold);
        }
    }

    /**
     * Computes the combined historical failure baseline for a bank.
     * <p>
     * Per §5: {@code bank.historical_avg_failure_rate} =
     * {@code historical_td_rate + historical_deemed_approved_rate}.
     *
     * @return the combined rate, or 0.0 if data is unavailable
     */
    double computeHistoricalBaseline(Bank bank) {
        BigDecimal tdRate = bank.getHistoricalTdRate();
        BigDecimal daRate = bank.getHistoricalDeemedApprovedRate();

        double baseline = 0.0;
        if (tdRate != null) {
            baseline += tdRate.doubleValue();
        }
        if (daRate != null) {
            baseline += daRate.doubleValue();
        }
        return baseline;
    }
}
