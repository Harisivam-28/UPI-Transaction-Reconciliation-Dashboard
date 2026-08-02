package com.upi.reconcile.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO for {@code GET /api/transactions} — ARCHITECTURE.md §7.
 * <p>
 * Carries current state, penalty accrued, and countdown-relevant timestamps
 * so the frontend can render live badges without extra queries.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionDto {

    private UUID txnId;
    private String state;
    private BigDecimal amountInr;
    private BigDecimal penaltyAmountInr;
    private UUID remitterBankId;
    private String remitterBankName;
    private UUID beneficiaryBankId;
    private String beneficiaryBankName;
    private OffsetDateTime createdAt;
    private OffsetDateTime tatDeadline;
    private OffsetDateTime penaltyStartAt;
    private OffsetDateTime resolvedAt;
    private String declineCode;
    private String orderReference;
    private String mlClassification;
    private BigDecimal mlConfidence;
}
