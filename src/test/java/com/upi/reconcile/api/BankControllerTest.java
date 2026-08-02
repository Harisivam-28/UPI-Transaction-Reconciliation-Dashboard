package com.upi.reconcile.api;

import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller test for {@link BankController} using {@code @WebMvcTest}.
 * <p>
 * Verifies scorecard response shape, reliability sorting, and live counts.
 */
@WebMvcTest(BankController.class)
class BankControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankRepository bankRepository;

    @MockitoBean
    private TransactionRepository transactionRepository;

    // ── Fixtures ──────────────────────────────────────────────────

    private static final UUID BANK_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BANK_B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private Bank bankA() {
        return Bank.builder()
                .bankId(BANK_A_ID)
                .name("Reliable National Bank")
                .historicalBdRate(new BigDecimal("0.0100"))
                .historicalTdRate(new BigDecimal("0.0050"))
                .historicalDeemedApprovedRate(new BigDecimal("0.0020"))
                .build();
    }

    private Bank bankB() {
        return Bank.builder()
                .bankId(BANK_B_ID)
                .name("Unreliable Payments Bank")
                .historicalBdRate(new BigDecimal("0.0300"))
                .historicalTdRate(new BigDecimal("0.0200"))
                .historicalDeemedApprovedRate(new BigDecimal("0.0150"))
                .build();
    }

    private Transaction txn(UUID id, Bank remitter, TransactionState state) {
        return Transaction.builder()
                .txnId(id)
                .idempotencyKey("key-" + id)
                .remitterBank(remitter)
                .beneficiaryBank(remitter)
                .amountInr(new BigDecimal("1000.00"))
                .state(state)
                .createdAt(NOW)
                .build();
    }

    // ── Tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Returns scorecard sorted by reliability — most reliable first")
    void returnsScorecardSortedByReliability() throws Exception {
        Bank bankA = bankA();
        Bank bankB = bankB();

        when(bankRepository.findAll()).thenReturn(List.of(bankA, bankB));

        // Bank A: 3 SUCCESS, 0 failures → reliability = 1.0
        // Bank B: 1 SUCCESS, 1 TD, 1 DEEMED_APPROVED → reliability = 1/3 ≈ 0.33
        when(transactionRepository.findAll()).thenReturn(List.of(
                txn(UUID.randomUUID(), bankA, TransactionState.SUCCESS),
                txn(UUID.randomUUID(), bankA, TransactionState.SUCCESS),
                txn(UUID.randomUUID(), bankA, TransactionState.SUCCESS),
                txn(UUID.randomUUID(), bankB, TransactionState.SUCCESS),
                txn(UUID.randomUUID(), bankB, TransactionState.TECHNICAL_DECLINED),
                txn(UUID.randomUUID(), bankB, TransactionState.DEEMED_APPROVED)
        ));

        mockMvc.perform(get("/api/banks/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // Bank A should be first (higher reliability)
                .andExpect(jsonPath("$[0].bankName").value("Reliable National Bank"))
                .andExpect(jsonPath("$[0].totalTransactions").value(3))
                .andExpect(jsonPath("$[0].liveReliabilityScore",
                        greaterThanOrEqualTo(0.99)))
                // Bank B should be second (lower reliability)
                .andExpect(jsonPath("$[1].bankName").value("Unreliable Payments Bank"))
                .andExpect(jsonPath("$[1].totalTransactions").value(3));
    }

    @Test
    @DisplayName("Includes historical rates in scorecard")
    void includesHistoricalRates() throws Exception {
        Bank bank = bankA();
        when(bankRepository.findAll()).thenReturn(List.of(bank));
        when(transactionRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/banks/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].historicalBdRate").value(0.0100))
                .andExpect(jsonPath("$[0].historicalTdRate").value(0.0050))
                .andExpect(jsonPath("$[0].historicalDeemedApprovedRate").value(0.0020));
    }

    @Test
    @DisplayName("Returns live state counts per bank")
    void returnsLiveStateCounts() throws Exception {
        Bank bank = bankA();
        when(bankRepository.findAll()).thenReturn(List.of(bank));
        when(transactionRepository.findAll()).thenReturn(List.of(
                txn(UUID.randomUUID(), bank, TransactionState.SUCCESS),
                txn(UUID.randomUUID(), bank, TransactionState.SUCCESS),
                txn(UUID.randomUUID(), bank, TransactionState.PENDING_RECONCILIATION)
        ));

        mockMvc.perform(get("/api/banks/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveStateCounts.SUCCESS").value(2))
                .andExpect(jsonPath("$[0].liveStateCounts.PENDING_RECONCILIATION").value(1));
    }

    @Test
    @DisplayName("Returns empty scorecard when no banks exist")
    void returnsEmptyWhenNoBanks() throws Exception {
        when(bankRepository.findAll()).thenReturn(List.of());
        when(transactionRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/banks/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
