package com.upi.reconcile.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive unit tests for {@link StateMachine}.
 *
 * <p>Covers every row in ARCHITECTURE.md §2 (State Transition Table) and confirms
 * that every (state, event) pair NOT listed in the table throws
 * {@link IllegalStateTransitionException}.
 *
 * <p>Target: 100 % branch coverage of {@code StateMachine#transition}.
 */
class StateMachineTest {

    private StateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new StateMachine();
    }

    // -----------------------------------------------------------------------
    //  §2 Row-by-Row: Valid Transitions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("INITIATED transitions")
    class InitiatedTransitions {

        @Test
        @DisplayName("INITIATED + MATCH_FOUND → SUCCESS")
        void matchFound() {
            assertEquals(TransactionState.SUCCESS,
                    stateMachine.transition(TransactionState.INITIATED, TransactionEvent.MATCH_FOUND));
        }

        @Test
        @DisplayName("INITIATED + BD_CODE → BUSINESS_DECLINED")
        void bdCode() {
            assertEquals(TransactionState.BUSINESS_DECLINED,
                    stateMachine.transition(TransactionState.INITIATED, TransactionEvent.BD_CODE));
        }

        @Test
        @DisplayName("INITIATED + TD_CODE → TECHNICAL_DECLINED")
        void tdCode() {
            assertEquals(TransactionState.TECHNICAL_DECLINED,
                    stateMachine.transition(TransactionState.INITIATED, TransactionEvent.TD_CODE));
        }

        @Test
        @DisplayName("INITIATED + NO_CONFIRMATION → DEEMED_APPROVED")
        void noConfirmation() {
            assertEquals(TransactionState.DEEMED_APPROVED,
                    stateMachine.transition(TransactionState.INITIATED, TransactionEvent.NO_CONFIRMATION));
        }
    }

    @Nested
    @DisplayName("TECHNICAL_DECLINED transitions")
    class TechnicalDeclinedTransitions {

        @Test
        @DisplayName("TECHNICAL_DECLINED + RETRY_SUCCESS → SUCCESS")
        void retrySuccess() {
            assertEquals(TransactionState.SUCCESS,
                    stateMachine.transition(TransactionState.TECHNICAL_DECLINED, TransactionEvent.RETRY_SUCCESS));
        }

        @Test
        @DisplayName("TECHNICAL_DECLINED + RETRIES_EXHAUSTED → PENDING_RECONCILIATION")
        void retriesExhausted() {
            assertEquals(TransactionState.PENDING_RECONCILIATION,
                    stateMachine.transition(TransactionState.TECHNICAL_DECLINED, TransactionEvent.RETRIES_EXHAUSTED));
        }
    }

    @Nested
    @DisplayName("DEEMED_APPROVED transitions")
    class DeemedApprovedTransitions {

        @Test
        @DisplayName("DEEMED_APPROVED + RETRIES_EXHAUSTED → PENDING_RECONCILIATION (enters queue)")
        void entersQueue() {
            assertEquals(TransactionState.PENDING_RECONCILIATION,
                    stateMachine.transition(TransactionState.DEEMED_APPROVED, TransactionEvent.RETRIES_EXHAUSTED));
        }
    }

    @Nested
    @DisplayName("PENDING_RECONCILIATION transitions")
    class PendingReconciliationTransitions {

        @Test
        @DisplayName("PENDING_RECONCILIATION + BATCH_RESOLVED → AUTO_REVERSED")
        void batchResolved() {
            assertEquals(TransactionState.AUTO_REVERSED,
                    stateMachine.transition(TransactionState.PENDING_RECONCILIATION, TransactionEvent.BATCH_RESOLVED));
        }

        @Test
        @DisplayName("PENDING_RECONCILIATION + DEADLINE_PASSED → TAT_BREACHED")
        void deadlinePassed() {
            assertEquals(TransactionState.TAT_BREACHED,
                    stateMachine.transition(TransactionState.PENDING_RECONCILIATION, TransactionEvent.DEADLINE_PASSED));
        }
    }

    @Nested
    @DisplayName("TAT_BREACHED transitions")
    class TatBreachedTransitions {

        @Test
        @DisplayName("TAT_BREACHED + DEADLINE_PASSED → PENALTY_ACCRUING (immediately)")
        void immediatelyToPenaltyAccruing() {
            assertEquals(TransactionState.PENALTY_ACCRUING,
                    stateMachine.transition(TransactionState.TAT_BREACHED, TransactionEvent.DEADLINE_PASSED));
        }
    }

    @Nested
    @DisplayName("PENALTY_ACCRUING transitions")
    class PenaltyAccruingTransitions {

        @Test
        @DisplayName("PENALTY_ACCRUING + RESOLUTION_ARRIVED → RESOLVED_REFUNDED")
        void resolutionArrived() {
            assertEquals(TransactionState.RESOLVED_REFUNDED,
                    stateMachine.transition(TransactionState.PENALTY_ACCRUING, TransactionEvent.RESOLUTION_ARRIVED));
        }

        @Test
        @DisplayName("PENALTY_ACCRUING + ESCALATION_THRESHOLD_HIT → ESCALATED")
        void escalationThresholdHit() {
            assertEquals(TransactionState.ESCALATED,
                    stateMachine.transition(TransactionState.PENALTY_ACCRUING, TransactionEvent.ESCALATION_THRESHOLD_HIT));
        }
    }

    // -----------------------------------------------------------------------
    //  Full happy-path chains (multi-hop integration sanity)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Multi-hop transition chains")
    class TransitionChains {

        @Test
        @DisplayName("Happy path: INITIATED → SUCCESS")
        void happyPath() {
            TransactionState state = TransactionState.INITIATED;
            state = stateMachine.transition(state, TransactionEvent.MATCH_FOUND);
            assertEquals(TransactionState.SUCCESS, state);
        }

        @Test
        @DisplayName("TD retry success: INITIATED → TECHNICAL_DECLINED → SUCCESS")
        void tdRetrySuccess() {
            TransactionState state = TransactionState.INITIATED;
            state = stateMachine.transition(state, TransactionEvent.TD_CODE);
            assertEquals(TransactionState.TECHNICAL_DECLINED, state);

            state = stateMachine.transition(state, TransactionEvent.RETRY_SUCCESS);
            assertEquals(TransactionState.SUCCESS, state);
        }

        @Test
        @DisplayName("TD exhaustion → batch resolve: INITIATED → TD → PENDING → AUTO_REVERSED")
        void tdExhaustionBatchResolve() {
            TransactionState state = TransactionState.INITIATED;
            state = stateMachine.transition(state, TransactionEvent.TD_CODE);
            state = stateMachine.transition(state, TransactionEvent.RETRIES_EXHAUSTED);
            assertEquals(TransactionState.PENDING_RECONCILIATION, state);

            state = stateMachine.transition(state, TransactionEvent.BATCH_RESOLVED);
            assertEquals(TransactionState.AUTO_REVERSED, state);
        }

        @Test
        @DisplayName("Full penalty path: INITIATED → DA → PENDING → TAT → PENALTY → RESOLVED")
        void fullPenaltyPath() {
            TransactionState state = TransactionState.INITIATED;
            state = stateMachine.transition(state, TransactionEvent.NO_CONFIRMATION);
            assertEquals(TransactionState.DEEMED_APPROVED, state);

            state = stateMachine.transition(state, TransactionEvent.RETRIES_EXHAUSTED);
            assertEquals(TransactionState.PENDING_RECONCILIATION, state);

            state = stateMachine.transition(state, TransactionEvent.DEADLINE_PASSED);
            assertEquals(TransactionState.TAT_BREACHED, state);

            state = stateMachine.transition(state, TransactionEvent.DEADLINE_PASSED);
            assertEquals(TransactionState.PENALTY_ACCRUING, state);

            state = stateMachine.transition(state, TransactionEvent.RESOLUTION_ARRIVED);
            assertEquals(TransactionState.RESOLVED_REFUNDED, state);
        }

        @Test
        @DisplayName("Escalation path: INITIATED → DA → PENDING → TAT → PENALTY → ESCALATED")
        void escalationPath() {
            TransactionState state = TransactionState.INITIATED;
            state = stateMachine.transition(state, TransactionEvent.NO_CONFIRMATION);
            state = stateMachine.transition(state, TransactionEvent.RETRIES_EXHAUSTED);
            state = stateMachine.transition(state, TransactionEvent.DEADLINE_PASSED);
            state = stateMachine.transition(state, TransactionEvent.DEADLINE_PASSED);
            assertEquals(TransactionState.PENALTY_ACCRUING, state);

            state = stateMachine.transition(state, TransactionEvent.ESCALATION_THRESHOLD_HIT);
            assertEquals(TransactionState.ESCALATED, state);
        }
    }

    // -----------------------------------------------------------------------
    //  Invalid Transitions — terminal states reject ALL events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Terminal states reject all events")
    class TerminalStateRejection {

        private static final Set<TransactionState> TERMINAL_STATES = EnumSet.of(
                TransactionState.SUCCESS,
                TransactionState.BUSINESS_DECLINED,
                TransactionState.AUTO_REVERSED,
                TransactionState.RESOLVED_REFUNDED,
                TransactionState.ESCALATED
        );

        @ParameterizedTest(name = "{0} should reject all events")
        @EnumSource(value = TransactionState.class,
                names = {"SUCCESS", "BUSINESS_DECLINED", "AUTO_REVERSED", "RESOLVED_REFUNDED", "ESCALATED"})
        void terminalStatesRejectAllEvents(TransactionState terminalState) {
            assertTrue(terminalState.isTerminal(), terminalState + " should be flagged as terminal");

            for (TransactionEvent event : TransactionEvent.values()) {
                IllegalStateTransitionException ex = assertThrows(
                        IllegalStateTransitionException.class,
                        () -> stateMachine.transition(terminalState, event),
                        String.format("Expected rejection for terminal state %s + event %s", terminalState, event));

                assertEquals(terminalState, ex.getFromState());
                assertEquals(event, ex.getEvent());
                assertTrue(ex.getMessage().contains(terminalState.name()));
                assertTrue(ex.getMessage().contains(event.name()));
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Invalid Transitions — non-terminal states reject unlisted events
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Non-terminal states reject unlisted events")
    class NonTerminalInvalidTransitions {

        @Test
        @DisplayName("INITIATED rejects events outside {MATCH_FOUND, BD_CODE, TD_CODE, NO_CONFIRMATION}")
        void initiatedRejectsInvalidEvents() {
            Set<TransactionEvent> valid = EnumSet.of(
                    TransactionEvent.MATCH_FOUND,
                    TransactionEvent.BD_CODE,
                    TransactionEvent.TD_CODE,
                    TransactionEvent.NO_CONFIRMATION);

            for (TransactionEvent event : TransactionEvent.values()) {
                if (!valid.contains(event)) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> stateMachine.transition(TransactionState.INITIATED, event),
                            "INITIATED + " + event + " should be rejected");
                }
            }
        }

        @Test
        @DisplayName("TECHNICAL_DECLINED rejects events outside {RETRY_SUCCESS, RETRIES_EXHAUSTED}")
        void technicalDeclinedRejectsInvalidEvents() {
            Set<TransactionEvent> valid = EnumSet.of(
                    TransactionEvent.RETRY_SUCCESS,
                    TransactionEvent.RETRIES_EXHAUSTED);

            for (TransactionEvent event : TransactionEvent.values()) {
                if (!valid.contains(event)) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> stateMachine.transition(TransactionState.TECHNICAL_DECLINED, event),
                            "TECHNICAL_DECLINED + " + event + " should be rejected");
                }
            }
        }

        @Test
        @DisplayName("DEEMED_APPROVED rejects events outside {RETRIES_EXHAUSTED}")
        void deemedApprovedRejectsInvalidEvents() {
            Set<TransactionEvent> valid = EnumSet.of(TransactionEvent.RETRIES_EXHAUSTED);

            for (TransactionEvent event : TransactionEvent.values()) {
                if (!valid.contains(event)) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> stateMachine.transition(TransactionState.DEEMED_APPROVED, event),
                            "DEEMED_APPROVED + " + event + " should be rejected");
                }
            }
        }

        @Test
        @DisplayName("PENDING_RECONCILIATION rejects events outside {BATCH_RESOLVED, DEADLINE_PASSED}")
        void pendingReconciliationRejectsInvalidEvents() {
            Set<TransactionEvent> valid = EnumSet.of(
                    TransactionEvent.BATCH_RESOLVED,
                    TransactionEvent.DEADLINE_PASSED);

            for (TransactionEvent event : TransactionEvent.values()) {
                if (!valid.contains(event)) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> stateMachine.transition(TransactionState.PENDING_RECONCILIATION, event),
                            "PENDING_RECONCILIATION + " + event + " should be rejected");
                }
            }
        }

        @Test
        @DisplayName("TAT_BREACHED rejects events outside {DEADLINE_PASSED}")
        void tatBreachedRejectsInvalidEvents() {
            Set<TransactionEvent> valid = EnumSet.of(TransactionEvent.DEADLINE_PASSED);

            for (TransactionEvent event : TransactionEvent.values()) {
                if (!valid.contains(event)) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> stateMachine.transition(TransactionState.TAT_BREACHED, event),
                            "TAT_BREACHED + " + event + " should be rejected");
                }
            }
        }

        @Test
        @DisplayName("PENALTY_ACCRUING rejects events outside {RESOLUTION_ARRIVED, ESCALATION_THRESHOLD_HIT}")
        void penaltyAccruingRejectsInvalidEvents() {
            Set<TransactionEvent> valid = EnumSet.of(
                    TransactionEvent.RESOLUTION_ARRIVED,
                    TransactionEvent.ESCALATION_THRESHOLD_HIT);

            for (TransactionEvent event : TransactionEvent.values()) {
                if (!valid.contains(event)) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> stateMachine.transition(TransactionState.PENALTY_ACCRUING, event),
                            "PENALTY_ACCRUING + " + event + " should be rejected");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Null arguments
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null argument handling")
    class NullArguments {

        @Test
        @DisplayName("null currentState throws NullPointerException")
        void nullState() {
            assertThrows(NullPointerException.class,
                    () -> stateMachine.transition(null, TransactionEvent.MATCH_FOUND));
        }

        @Test
        @DisplayName("null event throws NullPointerException")
        void nullEvent() {
            assertThrows(NullPointerException.class,
                    () -> stateMachine.transition(TransactionState.INITIATED, null));
        }

        @Test
        @DisplayName("both null throws NullPointerException")
        void bothNull() {
            assertThrows(NullPointerException.class,
                    () -> stateMachine.transition(null, null));
        }
    }

    // -----------------------------------------------------------------------
    //  Exception message and accessor verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Exception details")
    class ExceptionDetails {

        @Test
        @DisplayName("IllegalStateTransitionException carries fromState and event")
        void exceptionAccessors() {
            IllegalStateTransitionException ex = assertThrows(
                    IllegalStateTransitionException.class,
                    () -> stateMachine.transition(TransactionState.SUCCESS, TransactionEvent.MATCH_FOUND));

            assertEquals(TransactionState.SUCCESS, ex.getFromState());
            assertEquals(TransactionEvent.MATCH_FOUND, ex.getEvent());
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("SUCCESS"));
            assertTrue(ex.getMessage().contains("MATCH_FOUND"));
        }
    }

    // -----------------------------------------------------------------------
    //  Full matrix cross-check: every (state × event) pair is either valid
    //  or rejected — nothing falls through silently
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Full matrix sanity check")
    class FullMatrixCheck {

        /** Total valid transitions per ARCHITECTURE.md §2. */
        private static final int EXPECTED_VALID_TRANSITIONS = 12;

        @Test
        @DisplayName("Exactly 12 valid transitions exist in the state machine")
        void exactlyTwelveValidTransitions() {
            int validCount = 0;
            for (TransactionState state : TransactionState.values()) {
                for (TransactionEvent event : TransactionEvent.values()) {
                    try {
                        TransactionState result = stateMachine.transition(state, event);
                        assertNotNull(result, "Valid transition must return a non-null state");
                        validCount++;
                    } catch (IllegalStateTransitionException ignored) {
                        // expected for invalid pairs
                    }
                }
            }
            assertEquals(EXPECTED_VALID_TRANSITIONS, validCount,
                    "Number of valid (state, event) pairs must match ARCHITECTURE.md §2 table rows");
        }
    }
}
