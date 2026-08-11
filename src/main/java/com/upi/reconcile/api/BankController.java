package com.upi.reconcile.api;

import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bank scorecard endpoint — ARCHITECTURE.md §7.
 * <p>
 * GET /api/banks/scorecard
 *   → per-bank real + live-simulated BD/TD rate, avg resolution time, ranked
 * <p>
 * Sorted by reliability: fewest (TD + DEEMED_APPROVED) per total transactions.
 */
@Slf4j
@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankRepository bankRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping("/scorecard")
    public ResponseEntity<List<BankScorecardDto>> getBankScorecard() {
        log.info("Fetching bank scorecard");

        UUID merchantId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        List<Bank> banks = bankRepository.findAll();
        List<Transaction> allTransactions = transactionRepository.findAll(TransactionSpecifications.hasMerchantId(merchantId));

        // Group transactions by remitter bank for live counts
        Map<UUID, Map<TransactionState, Long>> bankStateCounts = new HashMap<>();
        Map<UUID, Long> bankTotals = new HashMap<>();

        for (Transaction txn : allTransactions) {
            if (txn.getRemitterBank() == null) continue;
            UUID bankId = txn.getRemitterBank().getBankId();

            bankStateCounts
                    .computeIfAbsent(bankId, k -> new EnumMap<>(TransactionState.class))
                    .merge(txn.getState(), 1L, Long::sum);

            bankTotals.merge(bankId, 1L, Long::sum);
        }

        List<BankScorecardDto> scorecards = banks.stream()
                .map(bank -> {
                    UUID bankId = bank.getBankId();
                    Map<TransactionState, Long> stateCounts =
                            bankStateCounts.getOrDefault(bankId, new EnumMap<>(TransactionState.class));
                    long total = bankTotals.getOrDefault(bankId, 0L);

                    // Convert EnumMap keys to String for JSON serialization
                    Map<String, Long> liveStateCounts = new HashMap<>();
                    stateCounts.forEach((state, count) -> liveStateCounts.put(state.name(), count));

                    // Reliability = 1 - (TD + DEEMED_APPROVED) / total
                    long failures = stateCounts.getOrDefault(TransactionState.TECHNICAL_DECLINED, 0L)
                            + stateCounts.getOrDefault(TransactionState.DEEMED_APPROVED, 0L);
                    BigDecimal reliability;
                    if (total == 0) {
                        reliability = BigDecimal.ONE; // no data → assume reliable
                    } else {
                        reliability = BigDecimal.ONE.subtract(
                                BigDecimal.valueOf(failures)
                                        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP));
                    }

                    return BankScorecardDto.builder()
                            .bankId(bankId)
                            .bankName(bank.getName())
                            .historicalBdRate(bank.getHistoricalBdRate())
                            .historicalTdRate(bank.getHistoricalTdRate())
                            .historicalDeemedApprovedRate(bank.getHistoricalDeemedApprovedRate())
                            .liveStateCounts(liveStateCounts)
                            .totalTransactions(total)
                            .liveReliabilityScore(reliability)
                            .build();
                })
                // Sort by reliability descending (most reliable first)
                .sorted(Comparator.comparing(BankScorecardDto::getLiveReliabilityScore).reversed())
                .toList();

        return ResponseEntity.ok(scorecards);
    }
}
