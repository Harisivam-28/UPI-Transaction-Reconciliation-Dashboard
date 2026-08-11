package com.upi.reconcile.scheduler;

import com.upi.reconcile.config.TimeCompressionConfig;
import com.upi.reconcile.connectors.GatewayResolutionService;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import com.upi.reconcile.ml.MlClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Batch resolution scheduler — ARCHITECTURE.md §3 & §4.
 * <p>
 * Runs at {@code BATCH_TICK_INTERVAL} (≈ every 7 seconds in demo mode) and performs
 * four sequential sweeps on each tick:
 * <ol>
 *   <li><b>Resolution attempt</b> — sweep DEEMED_APPROVED & PENDING_RECONCILIATION,
 *       sample resolution probability using the remitter bank's historical TD rate</li>
 *   <li><b>TAT breach detection</b> — transition unresolved txns past their deadline
 *       to TAT_BREACHED → PENALTY_ACCRUING immediately</li>
 *   <li><b>Penalty recomputation</b> — recalculate penalty_amount_inr for all
 *       PENALTY_ACCRUING transactions using the §4 formula</li>
 *   <li><b>Escalation check</b> — transition txns past the escalation threshold
 *       to ESCALATED</li>
 * </ol>
 * <p>
 * Every state change is published as a {@link TransactionStateChangedEvent}
 * so that downstream layers (e.g. WebSocket) can react without coupling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchResolutionScheduler {

    private final TransactionRepository transactionRepository;
    private final StateTransitionRepository stateTransitionRepository;
    private final StateMachine stateMachine;
    private final PenaltyEngine penaltyEngine;
    private final TimeCompressionConfig timeConfig;
    private final ApplicationEventPublisher eventPublisher;
    private final MlClassificationService mlClassificationService;
    private final GatewayResolutionService gatewayResolutionService;

    @Scheduled(fixedDelayString = "${reconciliation.batch-tick-interval-seconds:7}000")
    public void runBatchResolution() {
        OffsetDateTime now = OffsetDateTime.now();
        log.info("⏰ Batch resolution tick at {}", now);

        // Sweep 0: Gateway-driven resolution (real API calls for Razorpay txns)
        gatewayResolutionService.resolveRazorpayTransactions(now);

        sweepResolution(now);
        sweepTatBreach(now);
        sweepPenaltyRecomputation(now);
        sweepEscalation(now);
    }

    // ── Sweep 1: Resolution attempt ──────────────────────────────────────

    /**
     * Attempts resolution for DEEMED_APPROVED and PENDING_RECONCILIATION txns.
     * <p>
     * DEEMED_APPROVED txns are first moved to PENDING_RECONCILIATION (§2: "Enters queue"),
     * then all PENDING_RECONCILIATION txns are sampled for auto-reversal.
     * <p>
     * Resolution probability = {@code 1.0 - remitterBank.historicalTdRate}.
     * Higher historical TD rate → lower chance of clean auto-resolution this tick.
     */
    void sweepResolution(OffsetDateTime now) {
        // Move DEEMED_APPROVED → PENDING_RECONCILIATION first
        List<Transaction> deemedApproved =
                transactionRepository.findByState(TransactionState.DEEMED_APPROVED);

        for (Transaction txn : deemedApproved) {
            try {
                applyTransition(txn, TransactionEvent.RETRIES_EXHAUSTED,
                        "DEEMED_APPROVED entering reconciliation queue", now);
            } catch (Exception e) {
                log.error("Failed to queue DEEMED_APPROVED txn {}: {}", txn.getTxnId(), e.getMessage());
            }
        }

        // Now attempt resolution for all PENDING_RECONCILIATION
        List<Transaction> pending =
                transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION);

        for (Transaction txn : pending) {
            try {
                // Tag each transaction with ML classification before processing
                if (txn.getMlClassification() == null) {
                    mlClassificationService.classify(txn);
                }

                double resolutionProbability = computeResolutionProbability(txn);
                double roll = ThreadLocalRandom.current().nextDouble();

                if (roll < resolutionProbability) {
                    applyTransition(txn, TransactionEvent.BATCH_RESOLVED,
                            String.format("Batch auto-reversal succeeded (roll=%.4f < prob=%.4f)",
                                    roll, resolutionProbability),
                            now);
                    txn.setResolvedAt(now);
                    transactionRepository.save(txn);

                    log.info("✅ Txn {} auto-reversed (bank={}, prob={})",
                            txn.getTxnId(),
                            txn.getRemitterBank().getName(),
                            resolutionProbability);
                } else {
                    log.debug("❌ Txn {} not resolved this tick (roll={} >= prob={})",
                            txn.getTxnId(), roll, resolutionProbability);
                }
            } catch (Exception e) {
                log.error("Failed resolution attempt for txn {}: {}", txn.getTxnId(), e.getMessage());
            }
        }
    }

    // ── Sweep 2: TAT deadline breach ─────────────────────────────────────

    /**
     * Transitions PENDING_RECONCILIATION txns past their TAT deadline
     * to TAT_BREACHED, then immediately to PENALTY_ACCRUING (§2).
     */
    void sweepTatBreach(OffsetDateTime now) {
        List<Transaction> pending =
                transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION);

        for (Transaction txn : pending) {
            try {
                if (txn.getTatDeadline() != null && !now.isBefore(txn.getTatDeadline())) {
                    // PENDING_RECONCILIATION → TAT_BREACHED
                    applyTransition(txn, TransactionEvent.DEADLINE_PASSED,
                            "T+1 deadline passed — TAT breached", now);

                    // TAT_BREACHED → PENALTY_ACCRUING (immediately, per §2)
                    applyTransition(txn, TransactionEvent.DEADLINE_PASSED,
                            "Immediate transition — penalty clock starts at T+2", now);

                    log.info("⚠️ Txn {} TAT breached → penalty accruing (deadline={})",
                            txn.getTxnId(), txn.getTatDeadline());
                }
            } catch (Exception e) {
                log.error("Failed TAT breach processing for txn {}: {}",
                        txn.getTxnId(), e.getMessage());
            }
        }
    }

    // ── Sweep 3: Penalty recomputation ───────────────────────────────────

    /**
     * Recomputes {@code penalty_amount_inr} for all PENALTY_ACCRUING transactions
     * using the §4 formula.
     */
    void sweepPenaltyRecomputation(OffsetDateTime now) {
        List<Transaction> accruing =
                transactionRepository.findByState(TransactionState.PENALTY_ACCRUING);

        for (Transaction txn : accruing) {
            try {
                if (txn.getPenaltyStartAt() == null) {
                    log.warn("Txn {} in PENALTY_ACCRUING but has no penaltyStartAt — skipping",
                            txn.getTxnId());
                    continue;
                }

                BigDecimal penalty = penaltyEngine.calculate(
                        txn.getPenaltyStartAt(), now, timeConfig.getSimulatedDaySeconds());
                txn.setPenaltyAmountInr(penalty);
                transactionRepository.save(txn);

                log.debug("💰 Txn {} penalty recomputed: ₹{}", txn.getTxnId(), penalty);
            } catch (Exception e) {
                log.error("Failed penalty recomputation for txn {}: {}",
                        txn.getTxnId(), e.getMessage());
            }
        }
    }

    // ── Sweep 4: Escalation check ────────────────────────────────────────

    /**
     * Transitions PENALTY_ACCRUING txns to ESCALATED if they've exceeded
     * the escalation threshold (demo: T+3).
     */
    void sweepEscalation(OffsetDateTime now) {
        List<Transaction> accruing =
                transactionRepository.findByState(TransactionState.PENALTY_ACCRUING);

        for (Transaction txn : accruing) {
            try {
                OffsetDateTime escalationDeadline = txn.getTatDeadline()
                        .plusSeconds(timeConfig.getEscalationThresholdSeconds());

                if (!now.isBefore(escalationDeadline)) {
                    // Lock penalty at final value before escalation
                    BigDecimal finalPenalty = penaltyEngine.calculate(
                            txn.getPenaltyStartAt(), now, timeConfig.getSimulatedDaySeconds());
                    txn.setPenaltyAmountInr(finalPenalty);

                    applyTransition(txn, TransactionEvent.ESCALATION_THRESHOLD_HIT,
                            String.format("Escalation threshold exceeded — penalty locked at ₹%s",
                                    finalPenalty),
                            now);
                    txn.setResolvedAt(now);
                    transactionRepository.save(txn);

                    log.info("🚨 Txn {} escalated — penalty locked at ₹{}", txn.getTxnId(), finalPenalty);
                }
            } catch (Exception e) {
                log.error("Failed escalation check for txn {}: {}",
                        txn.getTxnId(), e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Applies a state transition via the state machine, persists the audit record,
     * updates the transaction state, and publishes a Spring ApplicationEvent.
     */
    @Transactional
    public void applyTransition(Transaction txn,
                                 TransactionEvent event,
                                 String reason,
                                 OffsetDateTime at) {
        TransactionState fromState = txn.getState();
        TransactionState toState = stateMachine.transition(fromState, event);

        // Update entity
        txn.setState(toState);
        transactionRepository.save(txn);

        // Record audit trail
        StateTransition st = StateTransition.builder()
                .transaction(txn)
                .fromState(fromState.name())
                .toState(toState.name())
                .transitionedAt(at)
                .reason(reason)
                .build();
        stateTransitionRepository.save(st);

        // Publish event for downstream listeners (e.g. WebSocket layer)
        eventPublisher.publishEvent(new TransactionStateChangedEvent(
                this,
                txn.getTxnId(),
                fromState,
                toState,
                txn.getPenaltyAmountInr(),
                txn.getRemitterBank() != null ? txn.getRemitterBank().getBankId() : null,
                txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getBankId() : null,
                at));

        log.debug("Transition: {} → {} [{}] for txn {}", fromState, toState, reason, txn.getTxnId());
    }

    /**
     * Computes resolution probability for a transaction based on its
     * remitter bank's historical TD rate.
     * <p>
     * Higher historical TD rate → lower chance of clean auto-resolution.
     * Resolution probability = 1.0 - historicalTdRate.
     * Falls back to 0.85 if no rate is available.
     */
    double computeResolutionProbability(Transaction txn) {
        Bank bank = txn.getRemitterBank();
        if (bank == null || bank.getHistoricalTdRate() == null) {
            return 0.85; // sensible default — 85% chance of resolution
        }
        return 1.0 - bank.getHistoricalTdRate().doubleValue();
    }
}
