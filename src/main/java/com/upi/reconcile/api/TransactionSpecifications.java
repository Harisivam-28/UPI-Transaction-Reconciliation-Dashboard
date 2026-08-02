package com.upi.reconcile.api;

import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionState;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * JPA Specifications for dynamic transaction filtering — ARCHITECTURE.md §7.
 * <p>
 * Supports composable where-clauses for {@code state} and {@code bank_id}
 * (matching against either remitter or beneficiary bank).
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
        // utility class
    }

    /**
     * Filters by exact transaction state.
     */
    public static Specification<Transaction> hasState(TransactionState state) {
        return (root, query, cb) -> cb.equal(root.get("state"), state);
    }

    /**
     * Filters where the given bankId is either the remitter or beneficiary.
     */
    public static Specification<Transaction> involvesBankId(UUID bankId) {
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("remitterBank").get("bankId"), bankId),
                cb.equal(root.get("beneficiaryBank").get("bankId"), bankId)
        );
    }
}
