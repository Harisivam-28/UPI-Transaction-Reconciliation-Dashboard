package com.upi.reconcile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bank entity — maps to ARCHITECTURE.md §6 {@code banks} table.
 * Seeded from real NPCI dataset with historical failure rates.
 */
@Entity
@Table(name = "banks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bank {

    @Id
    @Column(name = "bank_id")
    private UUID bankId;

    @Column(nullable = false)
    private String name;

    @Column(name = "historical_bd_rate", precision = 6, scale = 4)
    private BigDecimal historicalBdRate;

    @Column(name = "historical_td_rate", precision = 6, scale = 4)
    private BigDecimal historicalTdRate;

    @Column(name = "historical_deemed_approved_rate", precision = 6, scale = 4)
    private BigDecimal historicalDeemedApprovedRate;
}
