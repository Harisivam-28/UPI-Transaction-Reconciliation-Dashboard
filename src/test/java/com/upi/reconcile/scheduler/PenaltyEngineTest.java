package com.upi.reconcile.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link PenaltyEngine} — ARCHITECTURE.md §4 formula.
 * <pre>
 *   penalty_days = max(0, floor((resolution_or_now - PENALTY_START) / SIMULATED_DAY_SECONDS) + 1)
 *   penalty_amount_inr = penalty_days * 100
 * </pre>
 *
 * <p>All tests use SIMULATED_DAY_SECONDS = 20 (the demo default)
 * and a fixed base time to ensure deterministic results.
 */
class PenaltyEngineTest {

    /** Default simulated day length per ARCHITECTURE.md §3. */
    private static final int SIM_DAY = 20;

    private PenaltyEngine engine;

    /** Fixed base time — penalty starts here ("T+2" in simulated time). */
    private OffsetDateTime penaltyStart;

    @BeforeEach
    void setUp() {
        engine = new PenaltyEngine();
        penaltyStart = OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    // ── Happy-path edge cases ──────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases around penalty boundaries")
    class EdgeCases {

        @Test
        @DisplayName("Resolved before penalty starts → ₹0")
        void resolvedBeforeDeadline() {
            OffsetDateTime before = penaltyStart.minusSeconds(5);
            BigDecimal penalty = engine.calculate(penaltyStart, before, SIM_DAY);
            assertEquals(BigDecimal.ZERO, penalty,
                    "No penalty if resolved before penalty clock starts");
        }

        @Test
        @DisplayName("Resolved exactly at penalty start → ₹100 (floor(0/20)+1 = 1 day)")
        void resolvedExactlyAtDeadline() {
            BigDecimal penalty = engine.calculate(penaltyStart, penaltyStart, SIM_DAY);
            assertEquals(new BigDecimal("100"), penalty,
                    "Exactly at penalty start = 1 penalty day");
        }

        @Test
        @DisplayName("Resolved 1 tick late (7s) → ₹100 (floor(7/20)+1 = 1 day)")
        void resolvedOneTickLate() {
            OffsetDateTime ref = penaltyStart.plusSeconds(7);
            BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
            assertEquals(new BigDecimal("100"), penalty,
                    "7 seconds into penalty: floor(7/20)+1 = 1 day = ₹100");
        }

        @Test
        @DisplayName("Resolved 5 ticks late (35s) → ₹200 (floor(35/20)+1 = 2 days)")
        void resolvedFiveTicksLate() {
            OffsetDateTime ref = penaltyStart.plusSeconds(35);
            BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
            assertEquals(new BigDecimal("200"), penalty,
                    "35 seconds into penalty: floor(35/20)+1 = 2 days = ₹200");
        }
    }

    // ── "Never resolved" — ongoing accrual ─────────────────────────────

    @Nested
    @DisplayName("Never resolved (ongoing penalty accrual)")
    class NeverResolved {

        @Test
        @DisplayName("1 full simulated day late (20s) → ₹200 (floor(20/20)+1 = 2 days)")
        void oneDayLate() {
            OffsetDateTime ref = penaltyStart.plusSeconds(20);
            BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
            assertEquals(new BigDecimal("200"), penalty,
                    "20 seconds into penalty: floor(20/20)+1 = 2 days = ₹200");
        }

        @Test
        @DisplayName("2 full simulated days late (40s) → ₹300 (floor(40/20)+1 = 3 days)")
        void twoDaysLate() {
            OffsetDateTime ref = penaltyStart.plusSeconds(40);
            BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
            assertEquals(new BigDecimal("300"), penalty,
                    "40 seconds into penalty: floor(40/20)+1 = 3 days = ₹300");
        }

        @Test
        @DisplayName("19 seconds in (just under 1 full day) → ₹100 (floor(19/20)+1 = 1 day)")
        void justUnderOneDay() {
            OffsetDateTime ref = penaltyStart.plusSeconds(19);
            BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
            assertEquals(new BigDecimal("100"), penalty,
                    "19 seconds into penalty: floor(19/20)+1 = 1 day = ₹100");
        }
    }

    // ── Boundary and null safety ───────────────────────────────────────

    @Nested
    @DisplayName("Null input handling")
    class NullInputs {

        @Test
        @DisplayName("Null penaltyStart throws NullPointerException")
        void nullPenaltyStart() {
            assertThrows(NullPointerException.class,
                    () -> engine.calculate(null, OffsetDateTime.now(), SIM_DAY));
        }

        @Test
        @DisplayName("Null referenceTime throws NullPointerException")
        void nullReferenceTime() {
            assertThrows(NullPointerException.class,
                    () -> engine.calculate(penaltyStart, null, SIM_DAY));
        }
    }

    // ── Parameterised boundary sweep ───────────────────────────────────

    @Test
    @DisplayName("1 second before penalty start → ₹0")
    void oneSecondBeforeStart() {
        OffsetDateTime ref = penaltyStart.minusSeconds(1);
        BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
        assertEquals(BigDecimal.ZERO, penalty);
    }

    @Test
    @DisplayName("1 second into penalty → ₹100 (floor(1/20)+1 = 1)")
    void oneSecondAfterStart() {
        OffsetDateTime ref = penaltyStart.plusSeconds(1);
        BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
        assertEquals(new BigDecimal("100"), penalty);
    }

    @Test
    @DisplayName("Exact day boundaries: 60s = floor(60/20)+1 = 4 days = ₹400")
    void threeDaysLate() {
        OffsetDateTime ref = penaltyStart.plusSeconds(60);
        BigDecimal penalty = engine.calculate(penaltyStart, ref, SIM_DAY);
        assertEquals(new BigDecimal("400"), penalty);
    }
}
