package com.upi.reconcile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transaction entity — maps to ARCHITECTURE.md §6 {@code transactions} table.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @Column(name = "txn_id")
    private UUID txnId;

    @Column(name = "idempotency_key", unique = true, nullable = false)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "remitter_bank_id")
    private Bank remitterBank;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_bank_id")
    private Bank beneficiaryBank;

    @Column(name = "amount_inr", precision = 12, scale = 2)
    private BigDecimal amountInr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionState state;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "tat_deadline")
    private OffsetDateTime tatDeadline;

    @Column(name = "penalty_start_at")
    private OffsetDateTime penaltyStartAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "penalty_amount_inr", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal penaltyAmountInr = BigDecimal.ZERO;

    @Column(name = "decline_code")
    private String declineCode;

    @Column(name = "order_reference")
    private String orderReference;
}
