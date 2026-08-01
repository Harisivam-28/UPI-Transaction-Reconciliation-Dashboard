package com.upi.reconcile.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    List<Transaction> findByState(TransactionState state);

    List<Transaction> findByRemitterBank_BankId(UUID bankId);

    List<Transaction> findByBeneficiaryBank_BankId(UUID bankId);
}
