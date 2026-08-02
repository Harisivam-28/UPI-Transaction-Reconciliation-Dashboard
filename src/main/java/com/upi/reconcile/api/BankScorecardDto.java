package com.upi.reconcile.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for {@code GET /api/banks/scorecard} — ARCHITECTURE.md §7.
 * <p>
 * Joins each bank's historical NPCI failure rates with live counts of
 * transactions per state in the current session.  Sorted by reliability
 * (fewest TD + DEEMED_APPROVED per total).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankScorecardDto {

    private UUID bankId;
    private String bankName;

    // Historical rates seeded from NPCI dataset
    private BigDecimal historicalBdRate;
    private BigDecimal historicalTdRate;
    private BigDecimal historicalDeemedApprovedRate;

    // Live session counts: state → count
    private Map<String, Long> liveStateCounts;
    private long totalTransactions;

    // Reliability = 1 - (TD + DEEMED_APPROVED) / total  (higher is better)
    private BigDecimal liveReliabilityScore;
}
