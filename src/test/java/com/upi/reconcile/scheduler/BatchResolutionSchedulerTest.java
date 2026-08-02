package com.upi.reconcile.scheduler;

import com.upi.reconcile.config.TimeCompressionConfig;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-based unit tests for {@link BatchResolutionScheduler}.
 * Verifies sweep logic, state transitions, event publishing, and edge cases.
 */
@ExtendWith(MockitoExtension.class)
class BatchResolutionSchedulerTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StateTransitionRepository stateTransitionRepository;
    @Mock private StateMachine stateMachine;
    @Mock private PenaltyEngine penaltyEngine;
    @Mock private TimeCompressionConfig timeConfig;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BatchResolutionScheduler scheduler;

    @Captor private ArgumentCaptor<TransactionStateChangedEvent> eventCaptor;
    @Captor private ArgumentCaptor<StateTransition> transitionCaptor;
    @Captor private ArgumentCaptor<Transaction> txnCaptor;

    private static final int SIM_DAY = 20;
    private static final OffsetDateTime BASE_TIME =
            OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private Bank testBank;

    @BeforeEach
    void setUp() {
        testBank = Bank.builder()
                .bankId(UUID.randomUUID())
                .name("Test Bank")
                .historicalTdRate(new BigDecimal("0.05"))  // 5% TD rate → 95% resolution
                .historicalBdRate(new BigDecimal("0.02"))
                .build();
    }

    // ── Resolution probability ────────────────────────────────────────

    @Nested
    @DisplayName("Resolution probability computation")
    class ResolutionProbability {

        @Test
        @DisplayName("Uses 1.0 - historicalTdRate for resolution probability")
        void usesCorrectProbability() {
            Transaction txn = buildTxn(TransactionState.PENDING_RECONCILIATION, BASE_TIME);
            txn.getRemitterBank().setHistoricalTdRate(new BigDecimal("0.15"));

            double prob = scheduler.computeResolutionProbability(txn);
            assertEquals(0.85, prob, 0.0001);
        }

        @Test
        @DisplayName("Falls back to 0.85 when bank has no TD rate")
        void fallbackWhenNoRate() {
            Transaction txn = buildTxn(TransactionState.PENDING_RECONCILIATION, BASE_TIME);
            txn.getRemitterBank().setHistoricalTdRate(null);

            double prob = scheduler.computeResolutionProbability(txn);
            assertEquals(0.85, prob, 0.0001);
        }

        @Test
        @DisplayName("Falls back to 0.85 when remitter bank is null")
        void fallbackWhenNullBank() {
            Transaction txn = buildTxn(TransactionState.PENDING_RECONCILIATION, BASE_TIME);
            txn.setRemitterBank(null);

            double prob = scheduler.computeResolutionProbability(txn);
            assertEquals(0.85, prob, 0.0001);
        }
    }

    // ── Sweep 1: DEEMED_APPROVED → PENDING_RECONCILIATION ─────────────

    @Nested
    @DisplayName("Sweep 1: DEEMED_APPROVED queue entry")
    class DeemedApprovedSweep {

        @Test
        @DisplayName("DEEMED_APPROVED transitions to PENDING_RECONCILIATION")
        void deemedApprovedEntersQueue() {
            Transaction txn = buildTxn(TransactionState.DEEMED_APPROVED, BASE_TIME);

            when(transactionRepository.findByState(TransactionState.DEEMED_APPROVED))
                    .thenReturn(List.of(txn));
            when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                    .thenReturn(List.of());
            when(stateMachine.transition(TransactionState.DEEMED_APPROVED, TransactionEvent.RETRIES_EXHAUSTED))
                    .thenReturn(TransactionState.PENDING_RECONCILIATION);

            scheduler.sweepResolution(BASE_TIME);

            verify(stateMachine).transition(TransactionState.DEEMED_APPROVED, TransactionEvent.RETRIES_EXHAUSTED);
            verify(stateTransitionRepository).save(any(StateTransition.class));
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            TransactionStateChangedEvent event = eventCaptor.getValue();
            assertEquals(TransactionState.DEEMED_APPROVED, event.getFromState());
            assertEquals(TransactionState.PENDING_RECONCILIATION, event.getToState());
        }
    }

    // ── Sweep 2: TAT breach → PENALTY_ACCRUING ────────────────────────

    @Nested
    @DisplayName("Sweep 2: TAT deadline breach")
    class TatBreachSweep {

        @Test
        @DisplayName("Txn past TAT deadline chains PENDING → TAT_BREACHED → PENALTY_ACCRUING")
        void tatBreachChainsTransitions() {
            Transaction txn = buildTxn(TransactionState.PENDING_RECONCILIATION, BASE_TIME);
            txn.setTatDeadline(BASE_TIME.plusSeconds(SIM_DAY));

            OffsetDateTime afterDeadline = BASE_TIME.plusSeconds(SIM_DAY + 1);

            when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                    .thenReturn(List.of(txn));
            when(stateMachine.transition(TransactionState.PENDING_RECONCILIATION, TransactionEvent.DEADLINE_PASSED))
                    .thenReturn(TransactionState.TAT_BREACHED);
            when(stateMachine.transition(TransactionState.TAT_BREACHED, TransactionEvent.DEADLINE_PASSED))
                    .thenReturn(TransactionState.PENALTY_ACCRUING);

            scheduler.sweepTatBreach(afterDeadline);

            // Should have two transitions: PENDING→TAT_BREACHED and TAT_BREACHED→PENALTY_ACCRUING
            verify(stateMachine).transition(TransactionState.PENDING_RECONCILIATION, TransactionEvent.DEADLINE_PASSED);
            verify(stateMachine).transition(TransactionState.TAT_BREACHED, TransactionEvent.DEADLINE_PASSED);
            verify(stateTransitionRepository, times(2)).save(any(StateTransition.class));
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

            List<TransactionStateChangedEvent> events = eventCaptor.getAllValues();
            assertEquals(TransactionState.TAT_BREACHED, events.get(0).getToState());
            assertEquals(TransactionState.PENALTY_ACCRUING, events.get(1).getToState());
        }

        @Test
        @DisplayName("Txn before TAT deadline is not breached")
        void noBreachBeforeDeadline() {
            Transaction txn = buildTxn(TransactionState.PENDING_RECONCILIATION, BASE_TIME);
            txn.setTatDeadline(BASE_TIME.plusSeconds(SIM_DAY));

            OffsetDateTime beforeDeadline = BASE_TIME.plusSeconds(SIM_DAY - 1);

            when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                    .thenReturn(List.of(txn));

            scheduler.sweepTatBreach(beforeDeadline);

            verify(stateMachine, never()).transition(any(), any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ── Sweep 3: Penalty recomputation ────────────────────────────────

    @Nested
    @DisplayName("Sweep 3: Penalty recomputation")
    class PenaltyRecomputationSweep {

        @Test
        @DisplayName("Recomputes penalty for PENALTY_ACCRUING transactions")
        void recomputesPenalty() {
            Transaction txn = buildTxn(TransactionState.PENALTY_ACCRUING, BASE_TIME);
            txn.setPenaltyStartAt(BASE_TIME.plusSeconds(SIM_DAY * 2));

            OffsetDateTime now = BASE_TIME.plusSeconds(SIM_DAY * 3);

            when(transactionRepository.findByState(TransactionState.PENALTY_ACCRUING))
                    .thenReturn(List.of(txn));
            when(timeConfig.getSimulatedDaySeconds()).thenReturn(SIM_DAY);
            when(penaltyEngine.calculate(txn.getPenaltyStartAt(), now, SIM_DAY))
                    .thenReturn(new BigDecimal("100"));

            scheduler.sweepPenaltyRecomputation(now);

            verify(transactionRepository).save(txnCaptor.capture());
            assertEquals(new BigDecimal("100"), txnCaptor.getValue().getPenaltyAmountInr());
        }

        @Test
        @DisplayName("Skips txns with null penaltyStartAt")
        void skipsNullPenaltyStart() {
            Transaction txn = buildTxn(TransactionState.PENALTY_ACCRUING, BASE_TIME);
            txn.setPenaltyStartAt(null);

            when(transactionRepository.findByState(TransactionState.PENALTY_ACCRUING))
                    .thenReturn(List.of(txn));

            scheduler.sweepPenaltyRecomputation(BASE_TIME);

            verify(penaltyEngine, never()).calculate(any(), any(), eq(SIM_DAY));
        }
    }

    // ── Sweep 4: Escalation ───────────────────────────────────────────

    @Nested
    @DisplayName("Sweep 4: Escalation check")
    class EscalationSweep {

        @Test
        @DisplayName("Escalates txn past escalation threshold")
        void escalatesPastThreshold() {
            Transaction txn = buildTxn(TransactionState.PENALTY_ACCRUING, BASE_TIME);
            txn.setTatDeadline(BASE_TIME.plusSeconds(SIM_DAY));
            txn.setPenaltyStartAt(BASE_TIME.plusSeconds(SIM_DAY * 2));

            OffsetDateTime now = BASE_TIME.plusSeconds(SIM_DAY * 4); // well past T+3

            when(transactionRepository.findByState(TransactionState.PENALTY_ACCRUING))
                    .thenReturn(List.of(txn));
            when(timeConfig.getEscalationThresholdSeconds()).thenReturn(SIM_DAY * 2);
            when(timeConfig.getSimulatedDaySeconds()).thenReturn(SIM_DAY);
            when(penaltyEngine.calculate(any(), eq(now), eq(SIM_DAY)))
                    .thenReturn(new BigDecimal("200"));
            when(stateMachine.transition(TransactionState.PENALTY_ACCRUING, TransactionEvent.ESCALATION_THRESHOLD_HIT))
                    .thenReturn(TransactionState.ESCALATED);

            scheduler.sweepEscalation(now);

            verify(stateMachine).transition(TransactionState.PENALTY_ACCRUING, TransactionEvent.ESCALATION_THRESHOLD_HIT);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertEquals(TransactionState.ESCALATED, eventCaptor.getValue().getToState());
        }

        @Test
        @DisplayName("Does not escalate txn before threshold")
        void doesNotEscalateBeforeThreshold() {
            Transaction txn = buildTxn(TransactionState.PENALTY_ACCRUING, BASE_TIME);
            txn.setTatDeadline(BASE_TIME.plusSeconds(SIM_DAY));
            txn.setPenaltyStartAt(BASE_TIME.plusSeconds(SIM_DAY * 2));

            OffsetDateTime now = BASE_TIME.plusSeconds(SIM_DAY * 2); // not yet past T+3

            when(transactionRepository.findByState(TransactionState.PENALTY_ACCRUING))
                    .thenReturn(List.of(txn));
            when(timeConfig.getEscalationThresholdSeconds()).thenReturn(SIM_DAY * 2);

            scheduler.sweepEscalation(now);

            verify(stateMachine, never()).transition(any(), any());
        }
    }

    // ── Event publishing ──────────────────────────────────────────────

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        @DisplayName("applyTransition publishes TransactionStateChangedEvent with correct fields")
        void publishesCorrectEvent() {
            Transaction txn = buildTxn(TransactionState.PENDING_RECONCILIATION, BASE_TIME);
            txn.setPenaltyAmountInr(new BigDecimal("300"));

            when(stateMachine.transition(TransactionState.PENDING_RECONCILIATION, TransactionEvent.BATCH_RESOLVED))
                    .thenReturn(TransactionState.AUTO_REVERSED);

            scheduler.applyTransition(txn, TransactionEvent.BATCH_RESOLVED, "Test reason", BASE_TIME);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            TransactionStateChangedEvent event = eventCaptor.getValue();

            assertEquals(txn.getTxnId(), event.getTxnId());
            assertEquals(TransactionState.PENDING_RECONCILIATION, event.getFromState());
            assertEquals(TransactionState.AUTO_REVERSED, event.getToState());
            assertEquals(new BigDecimal("300"), event.getPenaltyAmountInr());
            assertEquals(testBank.getBankId(), event.getRemitterBankId());
            assertNotNull(event.getTransitionedAt());
        }
    }

    // ── Test helpers ──────────────────────────────────────────────────

    private Transaction buildTxn(TransactionState state, OffsetDateTime createdAt) {
        return Transaction.builder()
                .txnId(UUID.randomUUID())
                .idempotencyKey("test-key-" + UUID.randomUUID())
                .remitterBank(testBank)
                .beneficiaryBank(testBank)
                .amountInr(new BigDecimal("500.00"))
                .state(state)
                .createdAt(createdAt)
                .tatDeadline(createdAt.plusSeconds(SIM_DAY))
                .penaltyStartAt(createdAt.plusSeconds(SIM_DAY * 2))
                .build();
    }
}
