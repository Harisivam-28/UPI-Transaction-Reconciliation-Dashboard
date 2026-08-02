package com.upi.reconcile.api;

import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction query and action endpoints — ARCHITECTURE.md §7.
 * <p>
 * GET  /api/transactions?state=&amp;bank_id=&amp;page=
 * GET  /api/transactions/:txn_id/history
 * POST /api/transactions/:txn_id/generate-complaint
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private static final int PAGE_SIZE = 20;

    /** RBI circular reference per ARCHITECTURE.md §7. */
    private static final String RBI_CIRCULAR_REF =
            "DPSS.CO.PD No.629/02.01.014/2019-20";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss XXX");

    private final TransactionRepository transactionRepository;
    private final StateTransitionRepository stateTransitionRepository;

    // ── GET /api/transactions ─────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<TransactionDto>> listTransactions(
            @RequestParam(required = false) String state,
            @RequestParam(name = "bank_id", required = false) UUID bankId,
            @RequestParam(defaultValue = "0") int page) {

        log.info("List transactions — state={}, bankId={}, page={}", state, bankId, page);

        Specification<Transaction> spec = Specification.where(null);

        if (state != null && !state.isBlank()) {
            TransactionState txnState = TransactionState.valueOf(state.trim().toUpperCase());
            spec = spec.and(TransactionSpecifications.hasState(txnState));
        }
        if (bankId != null) {
            spec = spec.and(TransactionSpecifications.involvesBankId(bankId));
        }

        PageRequest pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TransactionDto> result = transactionRepository.findAll(spec, pageable)
                .map(this::toDto);

        return ResponseEntity.ok(result);
    }

    // ── GET /api/transactions/{txnId}/history ─────────────────────

    @GetMapping("/{txnId}/history")
    public ResponseEntity<List<StateTransitionDto>> getTransactionHistory(
            @PathVariable UUID txnId) {

        log.info("Get history for txn {}", txnId);

        if (transactionRepository.findById(txnId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<StateTransitionDto> history = stateTransitionRepository
                .findByTransaction_TxnIdOrderByTransitionedAtAsc(txnId)
                .stream()
                .map(this::toHistoryDto)
                .toList();

        return ResponseEntity.ok(history);
    }

    // ── POST /api/transactions/{txnId}/generate-complaint ─────────

    @PostMapping("/{txnId}/generate-complaint")
    public ResponseEntity<Map<String, String>> generateComplaint(
            @PathVariable UUID txnId) {

        log.info("Generate complaint for txn {}", txnId);

        Transaction txn = transactionRepository.findById(txnId).orElse(null);
        if (txn == null) {
            return ResponseEntity.notFound().build();
        }

        String complaint = buildComplaintText(txn);
        return ResponseEntity.ok(Map.of("complaint", complaint));
    }

    // ── Mapping helpers ───────────────────────────────────────────

    private TransactionDto toDto(Transaction txn) {
        return TransactionDto.builder()
                .txnId(txn.getTxnId())
                .state(txn.getState().name())
                .amountInr(txn.getAmountInr())
                .penaltyAmountInr(txn.getPenaltyAmountInr())
                .remitterBankId(txn.getRemitterBank() != null ? txn.getRemitterBank().getBankId() : null)
                .remitterBankName(txn.getRemitterBank() != null ? txn.getRemitterBank().getName() : null)
                .beneficiaryBankId(txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getBankId() : null)
                .beneficiaryBankName(txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getName() : null)
                .createdAt(txn.getCreatedAt())
                .tatDeadline(txn.getTatDeadline())
                .penaltyStartAt(txn.getPenaltyStartAt())
                .resolvedAt(txn.getResolvedAt())
                .declineCode(txn.getDeclineCode())
                .orderReference(txn.getOrderReference())
                .mlClassification(txn.getMlClassification())
                .mlConfidence(txn.getMlConfidence())
                .build();
    }

    private StateTransitionDto toHistoryDto(StateTransition st) {
        return StateTransitionDto.builder()
                .fromState(st.getFromState())
                .toState(st.getToState())
                .transitionedAt(st.getTransitionedAt())
                .reason(st.getReason())
                .build();
    }

    // ── Complaint template ────────────────────────────────────────

    /**
     * Deterministic complaint text generator — no LLM, no external call.
     * Fills a fixed Ombudsman complaint template with transaction details.
     */
    String buildComplaintText(Transaction txn) {
        String createdDate = txn.getCreatedAt() != null
                ? txn.getCreatedAt().format(DATE_FMT) : "N/A";
        String resolvedDate = txn.getResolvedAt() != null
                ? txn.getResolvedAt().format(DATE_FMT) : "Unresolved";

        return """
                COMPLAINT TO THE BANKING OMBUDSMAN
                ==================================

                Subject: Non-adherence to RBI-mandated Turn Around Time (TAT)
                         for UPI transaction resolution

                Reference Circular: RBI/2019-20/67, %s
                                    ("Harmonisation of Turn Around Time (TAT) and
                                     Customer Compensation for Failed Transactions
                                     using Authorised Payment Systems")

                Transaction Details:
                  Transaction ID   : %s
                  Transaction Date : %s
                  Resolution Date  : %s
                  Amount (INR)     : ₹%s
                  Current State    : %s

                Compensation Claimed:
                  As per the above circular, the beneficiary bank is liable to pay
                  ₹100 per day of delay beyond the mandated TAT (T+1 working day)
                  for UPI credit-not-received complaints.

                  Computed Penalty : ₹%s

                The above transaction has breached the prescribed TAT without
                resolution. I request the Hon'ble Banking Ombudsman to direct the
                concerned bank to credit the compensation amount to my account
                as mandated by the RBI circular referenced above.

                This complaint is auto-generated from auditable system records.
                """.formatted(
                RBI_CIRCULAR_REF,
                txn.getTxnId(),
                createdDate,
                resolvedDate,
                txn.getAmountInr() != null ? txn.getAmountInr().toPlainString() : "N/A",
                txn.getState().name(),
                txn.getPenaltyAmountInr() != null ? txn.getPenaltyAmountInr().toPlainString() : "0"
        );
    }
}
