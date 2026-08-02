package com.upi.reconcile.scheduler;

import com.upi.reconcile.config.TimeCompressionConfig;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.SystemicAnomalyEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AnomalyMonitor} — ARCHITECTURE.md §5.
 * <p>
 * Pure Mockito tests (no Spring context, no DB) that verify:
 * <ol>
 *   <li>A burst of TD events for one bank triggers {@link SystemicAnomalyEvent}</li>
 *   <li>Normal background noise does NOT falsely trigger the event</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AnomalyMonitorTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BankRepository bankRepository;

    @Mock
    private TimeCompressionConfig timeConfig;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AnomalyMonitor anomalyMonitor;

    @Captor
    private ArgumentCaptor<SystemicAnomalyEvent> eventCaptor;

    private Bank testBank;
    private static final UUID BANK_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testBank = Bank.builder()
                .bankId(BANK_ID)
                .name("Test Bank Ltd")
                .historicalTdRate(new BigDecimal("0.0500"))        // 5%
                .historicalDeemedApprovedRate(new BigDecimal("0.0500")) // 5%
                .historicalBdRate(new BigDecimal("0.0200"))
                .build();
        // baseline = 0.05 + 0.05 = 0.10 → threshold = 0.10 * 3 = 0.30
    }

    // ── Test 1: Anomaly FIRES on burst ──────────────────────────────────

    @Test
    @DisplayName("Anomaly fires when burst of TD events for one bank breaches 3× baseline")
    void anomalyFires_whenBurstOfTdEventsForOneBank() {
        // Arrange: 60-second rolling window
        when(timeConfig.getAnomalyWindowSeconds()).thenReturn(60);
        when(bankRepository.findAll()).thenReturn(List.of(testBank));

        // 8 failures out of 10 total → failure rate = 0.80 > 0.30 threshold
        when(transactionRepository.countByRemitterBank_BankIdAndCreatedAtAfter(
                eq(BANK_ID), any(OffsetDateTime.class)))
                .thenReturn(10L);
        when(transactionRepository.countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                eq(BANK_ID), anyCollection(), any(OffsetDateTime.class)))
                .thenReturn(8L);

        // Act
        OffsetDateTime now = OffsetDateTime.now();
        anomalyMonitor.sweepAnomalies(now);

        // Assert: event published with correct details
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        SystemicAnomalyEvent fired = eventCaptor.getValue();
        assertEquals(BANK_ID, fired.getBankId());
        assertEquals("Test Bank Ltd", fired.getBankName());
        assertEquals(0.80, fired.getFailureRateNow(), 0.001);
        assertEquals(0.10, fired.getHistoricalBaseline(), 0.001);
        assertEquals(8L, fired.getAffectedTransactionCount());
        assertNotNull(fired.getDetectedAt());
    }

    // ── Test 2: Anomaly does NOT fire on normal noise ───────────────────

    @Test
    @DisplayName("Anomaly does NOT fire when failure rate is normal background noise")
    void anomalyDoesNotFire_whenNormalBackgroundNoise() {
        // Arrange: 60-second rolling window
        when(timeConfig.getAnomalyWindowSeconds()).thenReturn(60);
        when(bankRepository.findAll()).thenReturn(List.of(testBank));

        // 2 failures out of 100 total → failure rate = 0.02 < 0.30 threshold
        when(transactionRepository.countByRemitterBank_BankIdAndCreatedAtAfter(
                eq(BANK_ID), any(OffsetDateTime.class)))
                .thenReturn(100L);
        when(transactionRepository.countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                eq(BANK_ID), anyCollection(), any(OffsetDateTime.class)))
                .thenReturn(2L);

        // Act
        OffsetDateTime now = OffsetDateTime.now();
        anomalyMonitor.sweepAnomalies(now);

        // Assert: NO event published
        verify(eventPublisher, never()).publishEvent(any(SystemicAnomalyEvent.class));
    }

    // ── Test 3: Bank with zero txns in window is skipped ────────────────

    @Test
    @DisplayName("Bank with zero transactions in the rolling window is silently skipped")
    void bankWithZeroTransactions_isSkipped() {
        when(timeConfig.getAnomalyWindowSeconds()).thenReturn(60);
        when(bankRepository.findAll()).thenReturn(List.of(testBank));

        // Zero transactions in window
        when(transactionRepository.countByRemitterBank_BankIdAndCreatedAtAfter(
                eq(BANK_ID), any(OffsetDateTime.class)))
                .thenReturn(0L);

        // Act
        anomalyMonitor.sweepAnomalies(OffsetDateTime.now());

        // Assert: failure count query never called, no event published
        verify(transactionRepository, never())
                .countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                        any(), anyCollection(), any());
        verify(eventPublisher, never()).publishEvent(any(SystemicAnomalyEvent.class));
    }

    // ── Test 4: Rate at exactly the threshold does NOT fire ─────────────

    @Test
    @DisplayName("Failure rate exactly at 3× baseline does NOT fire (strict >)")
    void anomalyDoesNotFire_whenRateExactlyAtThreshold() {
        when(timeConfig.getAnomalyWindowSeconds()).thenReturn(60);
        when(bankRepository.findAll()).thenReturn(List.of(testBank));

        // 30 failures out of 100 total → failure rate = 0.30 == 0.30 threshold (not >)
        when(transactionRepository.countByRemitterBank_BankIdAndCreatedAtAfter(
                eq(BANK_ID), any(OffsetDateTime.class)))
                .thenReturn(100L);
        when(transactionRepository.countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                eq(BANK_ID), anyCollection(), any(OffsetDateTime.class)))
                .thenReturn(30L);

        // Act
        anomalyMonitor.sweepAnomalies(OffsetDateTime.now());

        // Assert: NOT fired — we use strict > not >=
        verify(eventPublisher, never()).publishEvent(any(SystemicAnomalyEvent.class));
    }

    // ── Test 5: Historical baseline computation ─────────────────────────

    @Test
    @DisplayName("Historical baseline sums TD rate and deemed-approved rate")
    void computeHistoricalBaseline_sumsTdAndDeemedApprovedRates() {
        double baseline = anomalyMonitor.computeHistoricalBaseline(testBank);
        assertEquals(0.10, baseline, 0.0001);
    }

    @Test
    @DisplayName("Historical baseline returns 0.0 when rates are null")
    void computeHistoricalBaseline_returnsZero_whenRatesNull() {
        Bank nullRatesBank = Bank.builder()
                .bankId(UUID.randomUUID())
                .name("Null Rate Bank")
                .build();

        double baseline = anomalyMonitor.computeHistoricalBaseline(nullRatesBank);
        assertEquals(0.0, baseline, 0.0001);
    }

    // ── Test 6: Multiple banks — only anomalous one triggers ────────────

    @Test
    @DisplayName("Only the bank with anomalous failure rate triggers an event")
    void onlyAnomalousBankTriggersEvent_whenMultipleBanksPresent() {
        UUID healthyBankId = UUID.randomUUID();
        Bank healthyBank = Bank.builder()
                .bankId(healthyBankId)
                .name("Healthy Bank")
                .historicalTdRate(new BigDecimal("0.0500"))
                .historicalDeemedApprovedRate(new BigDecimal("0.0500"))
                .build();

        when(timeConfig.getAnomalyWindowSeconds()).thenReturn(60);
        when(bankRepository.findAll()).thenReturn(List.of(testBank, healthyBank));

        // Test Bank: 8/10 failures → 0.80 > 0.30 → FIRE
        when(transactionRepository.countByRemitterBank_BankIdAndCreatedAtAfter(
                eq(BANK_ID), any(OffsetDateTime.class)))
                .thenReturn(10L);
        when(transactionRepository.countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                eq(BANK_ID), anyCollection(), any(OffsetDateTime.class)))
                .thenReturn(8L);

        // Healthy Bank: 1/50 failures → 0.02 < 0.30 → SKIP
        when(transactionRepository.countByRemitterBank_BankIdAndCreatedAtAfter(
                eq(healthyBankId), any(OffsetDateTime.class)))
                .thenReturn(50L);
        when(transactionRepository.countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
                eq(healthyBankId), anyCollection(), any(OffsetDateTime.class)))
                .thenReturn(1L);

        // Act
        anomalyMonitor.sweepAnomalies(OffsetDateTime.now());

        // Assert: exactly one event, for the anomalous bank
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertEquals(BANK_ID, eventCaptor.getValue().getBankId());
    }
}
