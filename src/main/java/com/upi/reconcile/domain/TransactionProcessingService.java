package com.upi.reconcile.domain;

import com.upi.reconcile.config.TimeCompressionConfig;
import com.upi.reconcile.ml.MlClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the full transactional flow for a new webhook event:
 * <ol>
 *   <li>Create {@link Transaction} in state {@code INITIATED}</li>
 *   <li>Record the initial {@link StateTransition} (null → INITIATED)</li>
 *   <li>Interpret the {@code decline_code} to determine the first event</li>
 *   <li>Run the {@link StateMachine} to compute the next state</li>
 *   <li>Record the second {@link StateTransition} (INITIATED → next state)</li>
 * </ol>
 *
 * <p>The entire method runs inside a single DB transaction so that on failure
 * nothing is partially committed — Kafka consumer will redeliver and the
 * idempotency check will prevent duplicates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionProcessingService {

    /** Business-declined codes per ARCHITECTURE.md §2. */
    private static final Set<String> BD_CODES = Set.of(
            "BAD_PIN", "INVALID_BENEFICIARY", "LIMIT_EXCEEDED");

    /** Technical-declined codes per ARCHITECTURE.md §2. */
    private static final Set<String> TD_CODES = Set.of(
            "MALFORMED_BANK_ID", "MISSING_EXCEPTION_CODE");

    private final TransactionRepository transactionRepository;
    private final StateTransitionRepository stateTransitionRepository;
    private final BankRepository bankRepository;
    private final StateMachine stateMachine;
    private final TimeCompressionConfig timeConfig;
    private final MlClassificationService mlClassificationService;

    /**
     * Process a brand-new webhook event end-to-end.
     *
     * @return the created transaction with its resolved state
     */
    @Transactional
    public Transaction processNewTransaction(String idempotencyKey,
                                             UUID remitterBankId,
                                             UUID beneficiaryBankId,
                                             BigDecimal amountInr,
                                             String orderReference,
                                             String declineCode,
                                             String sourceGateway,
                                             UUID merchantId) {

        OffsetDateTime now = OffsetDateTime.now();

        // Look up banks (must exist — seeded by BankSeedRunner)
        Bank remitter = bankRepository.findById(remitterBankId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown remitter bank: " + remitterBankId));
        Bank beneficiary = bankRepository.findById(beneficiaryBankId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown beneficiary bank: " + beneficiaryBankId));

        // 1. Create Transaction in INITIATED state
        Transaction txn = Transaction.builder()
                .txnId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .remitterBank(remitter)
                .beneficiaryBank(beneficiary)
                .amountInr(amountInr)
                .state(TransactionState.INITIATED)
                .createdAt(now)
                .tatDeadline(now.plusSeconds(timeConfig.getTatDeadlineSeconds()))
                .penaltyStartAt(now.plusSeconds(timeConfig.getPenaltyStartSeconds()))
                .declineCode(declineCode)
                .orderReference(orderReference)
                .sourceGateway(sourceGateway)
                .merchantId(merchantId)
                .build();
        transactionRepository.save(txn);

        // 2. Record initial state transition (null → INITIATED)
        recordTransition(txn, null, TransactionState.INITIATED, "Webhook received", now);

        // 3. Determine the first event based on decline_code
        TransactionEvent firstEvent = resolveInitialEvent(declineCode);

        // 4. Transition via state machine
        TransactionState nextState = stateMachine.transition(TransactionState.INITIATED, firstEvent);

        // 5. Update transaction state
        txn.setState(nextState);
        if (nextState.isTerminal()) {
            txn.setResolvedAt(now);
        }
        transactionRepository.save(txn);

        // 6. Record the second transition (INITIATED → nextState)
        String reason = declineCode != null
                ? "Decline code: " + declineCode
                : "Match found — amounts/IDs align";
        recordTransition(txn, TransactionState.INITIATED, nextState, reason, now);

        // 7. ML classification for transactions entering reconciliation/mismatch states
        if (nextState == TransactionState.PENDING_RECONCILIATION
                || nextState == TransactionState.DEEMED_APPROVED) {
            mlClassificationService.classify(txn);
        }

        log.info("Created transaction {} — INITIATED → {} (key={})",
                txn.getTxnId(), nextState, idempotencyKey);

        return txn;
    }

    /**
     * Maps an optional decline code string to the appropriate
     * {@link TransactionEvent} per ARCHITECTURE.md §2.
     */
    TransactionEvent resolveInitialEvent(String declineCode) {
        if (declineCode == null || declineCode.isBlank()) {
            return TransactionEvent.MATCH_FOUND;
        }
        String code = declineCode.trim().toUpperCase();
        if (BD_CODES.contains(code)) {
            return TransactionEvent.BD_CODE;
        }
        if (TD_CODES.contains(code)) {
            return TransactionEvent.TD_CODE;
        }
        if ("NO_CONFIRMATION".equals(code)) {
            return TransactionEvent.NO_CONFIRMATION;
        }
        // Unknown codes treated as technical decline for safety
        log.warn("Unknown decline code '{}' — treating as TECHNICAL_DECLINED", declineCode);
        return TransactionEvent.TD_CODE;
    }

    private void recordTransition(Transaction txn,
                                  TransactionState from,
                                  TransactionState to,
                                  String reason,
                                  OffsetDateTime at) {
        StateTransition st = StateTransition.builder()
                .transaction(txn)
                .fromState(from != null ? from.name() : null)
                .toState(to.name())
                .transitionedAt(at)
                .reason(reason)
                .build();
        stateTransitionRepository.save(st);
    }
}
