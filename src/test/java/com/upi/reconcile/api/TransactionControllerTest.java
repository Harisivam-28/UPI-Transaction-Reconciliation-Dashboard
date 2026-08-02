package com.upi.reconcile.api;

import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for {@link TransactionController} using {@code @WebMvcTest}.
 * <p>
 * Verifies request mapping, response shape, filter application, and
 * the deterministic complaint generator — all without Testcontainers.
 */
@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionRepository transactionRepository;

    @MockitoBean
    private StateTransitionRepository stateTransitionRepository;

    // ── Fixtures ──────────────────────────────────────────────────

    private static final UUID TXN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BANK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    private Transaction sampleTransaction() {
        Bank remitter = Bank.builder()
                .bankId(BANK_ID)
                .name("State Bank of India")
                .historicalBdRate(new BigDecimal("0.0200"))
                .historicalTdRate(new BigDecimal("0.0100"))
                .historicalDeemedApprovedRate(new BigDecimal("0.0050"))
                .build();

        return Transaction.builder()
                .txnId(TXN_ID)
                .idempotencyKey("idem-123")
                .remitterBank(remitter)
                .beneficiaryBank(remitter)
                .amountInr(new BigDecimal("1500.00"))
                .state(TransactionState.PENALTY_ACCRUING)
                .createdAt(NOW)
                .tatDeadline(NOW.plusSeconds(20))
                .penaltyStartAt(NOW.plusSeconds(40))
                .penaltyAmountInr(new BigDecimal("300.00"))
                .orderReference("ORD-001")
                .build();
    }

    // ── GET /api/transactions ─────────────────────────────────────

    @Nested
    @DisplayName("GET /api/transactions")
    class ListTransactions {

        @Test
        @DisplayName("Returns paginated transactions with correct DTO shape")
        void returnsPaginatedResults() throws Exception {
            Transaction txn = sampleTransaction();

            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(txn)));

            mockMvc.perform(get("/api/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].txnId").value(TXN_ID.toString()))
                    .andExpect(jsonPath("$.content[0].state").value("PENALTY_ACCRUING"))
                    .andExpect(jsonPath("$.content[0].amountInr").value(1500.00))
                    .andExpect(jsonPath("$.content[0].penaltyAmountInr").value(300.00))
                    .andExpect(jsonPath("$.content[0].remitterBankId").value(BANK_ID.toString()));
        }

        @Test
        @DisplayName("Accepts state filter parameter")
        void acceptsStateFilter() throws Exception {
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/transactions").param("state", "SUCCESS"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)));
        }

        @Test
        @DisplayName("Accepts bank_id filter parameter")
        void acceptsBankIdFilter() throws Exception {
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/transactions").param("bank_id", BANK_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)));
        }

        @Test
        @DisplayName("Returns empty page when no transactions match")
        void returnsEmptyPage() throws Exception {
            when(transactionRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/api/transactions")
                            .param("state", "ESCALATED")
                            .param("bank_id", BANK_ID.toString())
                            .param("page", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // ── GET /api/transactions/{txnId}/history ─────────────────────

    @Nested
    @DisplayName("GET /api/transactions/{txnId}/history")
    class TransactionHistory {

        @Test
        @DisplayName("Returns full audit trail for known transaction")
        void returnsAuditTrail() throws Exception {
            when(transactionRepository.findById(TXN_ID))
                    .thenReturn(Optional.of(sampleTransaction()));

            StateTransition st1 = StateTransition.builder()
                    .fromState(null)
                    .toState("INITIATED")
                    .transitionedAt(NOW)
                    .reason("Webhook received")
                    .build();
            StateTransition st2 = StateTransition.builder()
                    .fromState("INITIATED")
                    .toState("TECHNICAL_DECLINED")
                    .transitionedAt(NOW.plusSeconds(1))
                    .reason("Decline code: MALFORMED_BANK_ID")
                    .build();

            when(stateTransitionRepository.findByTransaction_TxnIdOrderByTransitionedAtAsc(TXN_ID))
                    .thenReturn(List.of(st1, st2));

            mockMvc.perform(get("/api/transactions/{txnId}/history", TXN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].toState").value("INITIATED"))
                    .andExpect(jsonPath("$[0].reason").value("Webhook received"))
                    .andExpect(jsonPath("$[1].fromState").value("INITIATED"))
                    .andExpect(jsonPath("$[1].toState").value("TECHNICAL_DECLINED"));
        }

        @Test
        @DisplayName("Returns 404 for unknown transaction ID")
        void returns404ForUnknownTxn() throws Exception {
            UUID unknownId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            when(transactionRepository.findById(unknownId)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/transactions/{txnId}/history", unknownId))
                    .andExpect(status().isNotFound());
        }
    }

    // ── POST /api/transactions/{txnId}/generate-complaint ─────────

    @Nested
    @DisplayName("POST /api/transactions/{txnId}/generate-complaint")
    class GenerateComplaint {

        @Test
        @DisplayName("Returns filled complaint template with RBI circular reference")
        void returnsFilledTemplate() throws Exception {
            when(transactionRepository.findById(TXN_ID))
                    .thenReturn(Optional.of(sampleTransaction()));

            mockMvc.perform(post("/api/transactions/{txnId}/generate-complaint", TXN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.complaint",
                            containsString("DPSS.CO.PD No.629/02.01.014/2019-20")))
                    .andExpect(jsonPath("$.complaint",
                            containsString(TXN_ID.toString())))
                    .andExpect(jsonPath("$.complaint",
                            containsString("300")))
                    .andExpect(jsonPath("$.complaint",
                            containsString("PENALTY_ACCRUING")))
                    .andExpect(jsonPath("$.complaint",
                            containsString("1500")));
        }

        @Test
        @DisplayName("Returns 404 for unknown transaction ID")
        void returns404ForUnknownTxn() throws Exception {
            UUID unknownId = UUID.fromString("99999999-9999-9999-9999-999999999999");
            when(transactionRepository.findById(unknownId)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/transactions/{txnId}/generate-complaint", unknownId))
                    .andExpect(status().isNotFound());
        }
    }
}
