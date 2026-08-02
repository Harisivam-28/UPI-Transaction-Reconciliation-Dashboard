package com.upi.reconcile.config;

import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link BankSeedRunner}.
 *
 * <p>Uses H2 in-memory database via the {@code test} profile.
 * Verifies that:
 * <ul>
 *   <li>Seed data loads correctly from the CSV on classpath</li>
 *   <li>Running the seed a second time does NOT duplicate rows (idempotency)</li>
 * </ul>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(BankSeedRunner.class)
class BankSeedRunnerTest {

    @Autowired
    private BankRepository bankRepository;

    @Autowired
    private BankSeedRunner bankSeedRunner;

    @BeforeEach
    void cleanDatabase() {
        bankRepository.deleteAll();
    }

    @Test
    @DisplayName("Seed loads all banks from CSV")
    void seedLoadsAllBanks() throws Exception {
        // Act
        bankSeedRunner.run();

        // Assert — CSV has 10 data rows (npci_bank_stats.csv)
        long count = bankRepository.count();
        assertEquals(10, count, "Expected 10 banks from the NPCI CSV");

        // Spot-check a specific bank
        Optional<Bank> hdfc = bankRepository.findByName("HDFC Bank Ltd.");
        assertTrue(hdfc.isPresent(), "HDFC Bank Ltd. should be present");
        assertEquals(
                new BigDecimal("0.0584"),
                hdfc.get().getHistoricalBdRate(),
                "HDFC BD rate should match CSV value"
        );
        assertEquals(
                new BigDecimal("0.0009"),
                hdfc.get().getHistoricalTdRate(),
                "HDFC TD rate should match CSV value"
        );
        assertEquals(
                new BigDecimal("0.0012"),
                hdfc.get().getHistoricalDeemedApprovedRate(),
                "HDFC deemed-approved rate should match CSV value"
        );
    }

    @Test
    @DisplayName("Seed is idempotent — running twice does not duplicate rows")
    void seedIsIdempotent() throws Exception {
        // First run
        bankSeedRunner.run();
        long countAfterFirst = bankRepository.count();
        assertEquals(10, countAfterFirst, "First seed should insert 10 banks");

        // Second run — should be a no-op
        bankSeedRunner.run();
        long countAfterSecond = bankRepository.count();
        assertEquals(10, countAfterSecond,
                "Second seed run should NOT insert additional rows (idempotency)");
    }

    @Test
    @DisplayName("Deterministic UUIDs are stable across runs")
    void deterministicUuidsAreStable() throws Exception {
        // Run seed twice (clean DB between)
        bankSeedRunner.run();
        Optional<Bank> axisFirst = bankRepository.findByName("Axis Bank Ltd.");
        assertTrue(axisFirst.isPresent());
        var firstId = axisFirst.get().getBankId();

        bankRepository.deleteAll();
        bankSeedRunner.run();
        Optional<Bank> axisSecond = bankRepository.findByName("Axis Bank Ltd.");
        assertTrue(axisSecond.isPresent());
        var secondId = axisSecond.get().getBankId();

        assertEquals(firstId, secondId,
                "Same bank name should always produce the same UUID");
    }

    @Test
    @DisplayName("All banks have non-null rates")
    void allBanksHaveRates() throws Exception {
        bankSeedRunner.run();

        bankRepository.findAll().forEach(bank -> {
            assertNotNull(bank.getHistoricalBdRate(),
                    bank.getName() + " should have BD rate");
            assertNotNull(bank.getHistoricalTdRate(),
                    bank.getName() + " should have TD rate");
            assertNotNull(bank.getHistoricalDeemedApprovedRate(),
                    bank.getName() + " should have deemed-approved rate");

            // Rates should be positive and within reasonable range (0–1)
            assertTrue(bank.getHistoricalBdRate().compareTo(BigDecimal.ZERO) > 0,
                    bank.getName() + " BD rate should be positive");
            assertTrue(bank.getHistoricalBdRate().compareTo(BigDecimal.ONE) < 0,
                    bank.getName() + " BD rate should be < 1.0");
        });
    }
}
