package com.upi.reconcile.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>,
        JpaSpecificationExecutor<Transaction> {

    List<Transaction> findByState(TransactionState state);

    List<Transaction> findByStateIn(Collection<TransactionState> states);

    List<Transaction> findByRemitterBank_BankId(UUID bankId);

    List<Transaction> findByBeneficiaryBank_BankId(UUID bankId);

    // ── Anomaly monitor queries (§5) ─────────────────────────────────────

    /**
     * Counts transactions for a given remitter bank that are in one of the
     * specified states and were created after the rolling-window cutoff.
     */
    long countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
            UUID bankId, Collection<TransactionState> states, OffsetDateTime after);

    /**
     * Counts all transactions for a given remitter bank created after the
     * rolling-window cutoff.
     */
    long countByRemitterBank_BankIdAndCreatedAtAfter(UUID bankId, OffsetDateTime after);
}
