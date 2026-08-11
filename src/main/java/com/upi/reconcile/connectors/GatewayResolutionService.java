package com.upi.reconcile.connectors;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Gateway-driven resolution service for Razorpay-sourced transactions.
 *
 * <p>When a transaction sourced from Razorpay enters {@code PENALTY_ACCRUING},
 * this service calls Razorpay's real API to determine the true payment status
 * and takes corrective action:
 *
 * <ul>
 *   <li>If Razorpay says {@code captured} → transition to {@code SUCCESS}
 *       (auto-corrected via gateway status check)</li>
 *   <li>If Razorpay says {@code failed}/{@code created}/{@code authorized} →
 *       initiate refund via API, then transition to {@code RESOLVED_REFUNDED}</li>
 *   <li>If Razorpay says {@code refunded} → transition to {@code RESOLVED_REFUNDED}
 *       (already refunded)</li>
 * </ul>
 *
 * <p>This is invoked from {@link com.upi.reconcile.scheduler.BatchResolutionScheduler}
 * as a new sweep that runs <em>before</em> the simulated NPCI batch resolution,
 * ensuring Razorpay transactions get real API-driven resolution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayResolutionService {

    private final TransactionRepository transactionRepository;
    private final StateTransitionRepository stateTransitionRepository;
    private final MerchantRepository merchantRepository;
    private final RazorpayApiClient razorpayApiClient;
    private final AesGcmEncryptor encryptor;
    private final StateMachine stateMachine;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Resolves all PENALTY_ACCRUING transactions sourced from Razorpay
     * by calling the real Razorpay Payments API.
     *
     * @param now the current timestamp for audit records
     */
    @Transactional
    public void resolveRazorpayTransactions(OffsetDateTime now) {
        List<Transaction> razorpayPenalty = transactionRepository
                .findByStateAndSourceGateway(TransactionState.PENALTY_ACCRUING, "razorpay");

        if (razorpayPenalty.isEmpty()) {
            return;
        }

        log.info("🔌 Gateway resolution sweep — {} Razorpay PENALTY_ACCRUING transactions",
                razorpayPenalty.size());

        // Look up Razorpay merchant credentials (use first connected merchant)
        List<Merchant> merchants = merchantRepository.findByConnectedGateway("razorpay");
        if (merchants.isEmpty()) {
            log.warn("No Razorpay merchant found — skipping gateway resolution");
            return;
        }

        Merchant merchant = merchants.getFirst();
        String apiKey;
        String apiSecret;
        try {
            apiKey = encryptor.decrypt(merchant.getEncryptedApiKey());
            apiSecret = encryptor.decrypt(merchant.getEncryptedApiSecret());
        } catch (Exception e) {
            log.error("Failed to decrypt Razorpay credentials for merchant {} — skipping",
                    merchant.getMerchantId(), e);
            return;
        }

        for (Transaction txn : razorpayPenalty) {
            try {
                resolveOneTransaction(txn, apiKey, apiSecret, now);
            } catch (Exception e) {
                log.error("Gateway resolution failed for txn {} (payment_id={}) — will retry next tick",
                        txn.getTxnId(), txn.getIdempotencyKey(), e);
            }
        }
    }

    /**
     * Resolves a single Razorpay transaction by checking its real payment status.
     */
    private void resolveOneTransaction(Transaction txn, String apiKey, String apiSecret,
                                        OffsetDateTime now) {
        String paymentId = txn.getIdempotencyKey(); // idempotency_key = Razorpay payment_id

        RazorpayApiClient.PaymentStatus status =
                razorpayApiClient.fetchPaymentStatus(apiKey, apiSecret, paymentId);

        switch (status.status()) {
            case "captured" -> {
                // Payment actually succeeded — webhook was just missed/delayed
                applyTransition(txn, TransactionEvent.GATEWAY_STATUS_CHECK_SUCCESS,
                        "Auto-corrected via Razorpay status check — payment confirmed successful", now);
                txn.setResolvedAt(now);
                transactionRepository.save(txn);

                log.info("✅ Txn {} auto-corrected to SUCCESS via Razorpay API (payment_id={})",
                        txn.getTxnId(), paymentId);
            }

            case "refunded" -> {
                // Already refunded on Razorpay's side
                applyTransition(txn, TransactionEvent.GATEWAY_REFUND_COMPLETED,
                        "Auto-corrected via Razorpay status check — already refunded on gateway", now);
                txn.setResolvedAt(now);
                transactionRepository.save(txn);

                log.info("✅ Txn {} confirmed REFUNDED via Razorpay API (payment_id={})",
                        txn.getTxnId(), paymentId);
            }

            case "failed", "created", "authorized" -> {
                // Payment genuinely failed or never completed — initiate refund
                try {
                    RazorpayApiClient.RefundResult refund =
                            razorpayApiClient.initiateRefund(apiKey, apiSecret, paymentId);

                    applyTransition(txn, TransactionEvent.GATEWAY_REFUND_COMPLETED,
                            String.format("Auto-refunded via Razorpay API — refund_id: %s, status: %s",
                                    refund.refundId(), refund.status()), now);
                    txn.setResolvedAt(now);
                    transactionRepository.save(txn);

                    log.info("💸 Txn {} auto-refunded via Razorpay API — refund_id={} (payment_id={})",
                            txn.getTxnId(), refund.refundId(), paymentId);
                } catch (Exception e) {
                    log.error("Razorpay refund failed for txn {} (payment_id={}) — {}",
                            txn.getTxnId(), paymentId, e.getMessage());
                }
            }

            default -> log.warn("Unexpected Razorpay payment status '{}' for payment_id={} — skipping",
                    status.status(), paymentId);
        }
    }

    /**
     * Applies a state transition, records audit trail, and publishes event.
     */
    private void applyTransition(Transaction txn, TransactionEvent event,
                                  String reason, OffsetDateTime at) {
        TransactionState fromState = txn.getState();
        TransactionState toState = stateMachine.transition(fromState, event);

        txn.setState(toState);
        transactionRepository.save(txn);

        StateTransition st = StateTransition.builder()
                .transaction(txn)
                .fromState(fromState.name())
                .toState(toState.name())
                .transitionedAt(at)
                .reason(reason)
                .build();
        stateTransitionRepository.save(st);

        eventPublisher.publishEvent(new TransactionStateChangedEvent(
                this,
                txn.getTxnId(),
                fromState,
                toState,
                txn.getPenaltyAmountInr(),
                txn.getRemitterBank() != null ? txn.getRemitterBank().getBankId() : null,
                txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getBankId() : null,
                at));

        log.debug("Transition: {} → {} [{}] for txn {}", fromState, toState, reason, txn.getTxnId());
    }
}
