package com.upi.reconcile.ml;

import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Orchestration layer for ML mismatch classification.
 * <p>
 * Builds a {@link MlPredictionRequest} from a {@link Transaction} entity,
 * calls the ML service, and persists the classification result and
 * confidence score on the transaction record.
 * <p>
 * This service is designed to be called from:
 * <ul>
 *   <li>{@code TransactionProcessingService} — at ingestion time for PENDING/DEEMED_APPROVED txns</li>
 *   <li>{@code BatchResolutionScheduler} — during each sweep for PENDING_RECONCILIATION txns</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlClassificationService {

    /** Business-declined codes per ARCHITECTURE.md §2. */
    private static final Set<String> BD_CODES = Set.of(
            "BAD_PIN", "INVALID_BENEFICIARY", "LIMIT_EXCEEDED");

    /** Technical-declined codes per ARCHITECTURE.md §2. */
    private static final Set<String> TD_CODES = Set.of(
            "MALFORMED_BANK_ID", "MISSING_EXCEPTION_CODE");

    private final MlServiceClient mlServiceClient;
    private final TransactionRepository transactionRepository;

    /**
     * Classify a transaction using the ML mismatch classifier.
     * <p>
     * Computes features from the transaction entity, calls the ML service,
     * and stores the result. If the ML service is unavailable, logs a
     * warning and continues — classification is best-effort.
     *
     * @param txn the transaction to classify
     */
    public void classify(Transaction txn) {
        try {
            MlPredictionRequest request = buildRequest(txn);
            MlPredictionResponse response = mlServiceClient.classify(request);

            if (response != null) {
                txn.setMlClassification(response.getClassification());
                txn.setMlConfidence(BigDecimal.valueOf(response.getConfidence()));
                transactionRepository.save(txn);

                log.info("ML classified txn {} → {} (confidence={:.4f})",
                        txn.getTxnId(),
                        response.getClassification(),
                        response.getConfidence());
            } else {
                log.warn("No ML classification returned for txn {} — service may be down",
                        txn.getTxnId());
            }
        } catch (Exception e) {
            log.warn("ML classification failed for txn {} (non-blocking): {}",
                    txn.getTxnId(), e.getMessage());
        }
    }

    /**
     * Build a classification request from a transaction entity.
     * <p>
     * Computes derived features:
     * <ul>
     *   <li>{@code timePendingSeconds} — seconds since transaction creation</li>
     *   <li>{@code declineCodeCategory} — maps raw decline code to BD/TD/none</li>
     *   <li>{@code remitterBankHistoricalTdRate} — from the bank entity</li>
     *   <li>{@code isDuplicateFlag} — false by default (set true by dedup layer)</li>
     * </ul>
     */
    MlPredictionRequest buildRequest(Transaction txn) {
        BigDecimal amount = txn.getAmountInr() != null ? txn.getAmountInr() : BigDecimal.ZERO;

        // Compute time pending in seconds
        long pendingSeconds = Duration.between(
                txn.getCreatedAt(), OffsetDateTime.now()
        ).getSeconds();

        // Map decline code to category
        String declineCategory = mapDeclineCodeToCategory(txn.getDeclineCode());

        // Get bank's historical TD rate
        double tdRate = 0.05; // sensible default
        Bank remitter = txn.getRemitterBank();
        if (remitter != null && remitter.getHistoricalTdRate() != null) {
            tdRate = remitter.getHistoricalTdRate().doubleValue();
        }

        return MlPredictionRequest.builder()
                .amountExpected(amount)
                .amountActual(amount)  // same unless mismatch detected upstream
                .amountDiffPct(0.0)    // computed upstream if mismatch detected
                .timePendingSeconds(pendingSeconds)
                .declineCodeCategory(declineCategory)
                .remitterBankHistoricalTdRate(tdRate)
                .isDuplicateFlag(false) // dedup layer sets this separately
                .build();
    }

    /**
     * Maps a raw decline code string to the ML model's categorical input.
     *
     * @return "BD", "TD", or "none"
     */
    String mapDeclineCodeToCategory(String declineCode) {
        if (declineCode == null || declineCode.isBlank()) {
            return "none";
        }
        String code = declineCode.trim().toUpperCase();
        if (BD_CODES.contains(code)) {
            return "BD";
        }
        if (TD_CODES.contains(code)) {
            return "TD";
        }
        // Unknown codes conservatively treated as TD
        return "TD";
    }
}
