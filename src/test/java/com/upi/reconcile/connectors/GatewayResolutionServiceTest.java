package com.upi.reconcile.connectors;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GatewayResolutionService} using mocked Razorpay API client.
 *
 * <p>Tests the four resolution scenarios:
 * <ol>
 *   <li>Razorpay status=captured → SUCCESS</li>
 *   <li>Razorpay status=failed → refund → RESOLVED_REFUNDED</li>
 *   <li>Non-Razorpay transactions are skipped</li>
 *   <li>Razorpay API errors are handled gracefully</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class GatewayResolutionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StateTransitionRepository stateTransitionRepository;
    @Mock private MerchantRepository merchantRepository;
    @Mock private RazorpayApiClient razorpayApiClient;
    @Mock private AesGcmEncryptor encryptor;
    @Mock private ApplicationEventPublisher eventPublisher;

    private GatewayResolutionService service;
    private StateMachine stateMachine;

    private static final String ENCRYPTED_KEY = "enc_key";
    private static final String ENCRYPTED_SECRET = "enc_secret";
    private static final String DECRYPTED_KEY = "rzp_test_abc123";
    private static final String DECRYPTED_SECRET = "secret_xyz789";

    @BeforeEach
    void setUp() {
        stateMachine = new StateMachine();
        service = new GatewayResolutionService(
                transactionRepository,
                stateTransitionRepository,
                merchantRepository,
                razorpayApiClient,
                encryptor,
                stateMachine,
                eventPublisher
        );
    }

    private Transaction buildPenaltyTransaction(String paymentId, String sourceGateway) {
        Bank remitter = Bank.builder().bankId(UUID.randomUUID()).name("HDFC Bank").build();
        Bank beneficiary = Bank.builder().bankId(UUID.randomUUID()).name("ICICI Bank").build();
        return Transaction.builder()
                .txnId(UUID.randomUUID())
                .idempotencyKey(paymentId)
                .remitterBank(remitter)
                .beneficiaryBank(beneficiary)
                .amountInr(BigDecimal.valueOf(500))
                .state(TransactionState.PENALTY_ACCRUING)
                .createdAt(OffsetDateTime.now().minusMinutes(30))
                .sourceGateway(sourceGateway)
                .build();
    }

    private Merchant buildMerchant() {
        return Merchant.builder()
                .merchantId(UUID.randomUUID())
                .name("Test Merchant")
                .connectedGateway("razorpay")
                .encryptedApiKey(ENCRYPTED_KEY)
                .encryptedApiSecret(ENCRYPTED_SECRET)
                .build();
    }

    // ── Test 1: Razorpay status=captured → SUCCESS ──────────────────

    @Test
    @DisplayName("Razorpay captured → transitions to SUCCESS with gateway reason")
    void razorpayCaptured_transitionsToSuccess() {
        String paymentId = "pay_TestCaptured123";
        Transaction txn = buildPenaltyTransaction(paymentId, "razorpay");
        Merchant merchant = buildMerchant();

        when(transactionRepository.findByStateAndSourceGateway(
                TransactionState.PENALTY_ACCRUING, "razorpay"))
                .thenReturn(List.of(txn));
        when(merchantRepository.findByConnectedGateway("razorpay"))
                .thenReturn(List.of(merchant));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.PaymentStatus(paymentId, "captured"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        service.resolveRazorpayTransactions(OffsetDateTime.now());

        // Assert — state changed to SUCCESS
        assertThat(txn.getState()).isEqualTo(TransactionState.SUCCESS);
        assertThat(txn.getResolvedAt()).isNotNull();

        // Assert — audit trail recorded with gateway-specific reason
        ArgumentCaptor<StateTransition> stCaptor = ArgumentCaptor.forClass(StateTransition.class);
        verify(stateTransitionRepository).save(stCaptor.capture());
        StateTransition saved = stCaptor.getValue();
        assertThat(saved.getFromState()).isEqualTo("PENALTY_ACCRUING");
        assertThat(saved.getToState()).isEqualTo("SUCCESS");
        assertThat(saved.getReason()).contains("via Razorpay");
        assertThat(saved.getReason()).contains("payment confirmed successful");

        // Assert — no refund call
        verify(razorpayApiClient, never()).initiateRefund(any(), any(), any());

        // Assert — event published
        verify(eventPublisher).publishEvent(any(TransactionStateChangedEvent.class));
    }

    // ── Test 2: Razorpay status=failed → refund → RESOLVED_REFUNDED ─

    @Test
    @DisplayName("Razorpay failed → refund triggered → transitions to RESOLVED_REFUNDED")
    void razorpayFailed_refundsAndTransitionsToResolvedRefunded() {
        String paymentId = "pay_TestFailed456";
        Transaction txn = buildPenaltyTransaction(paymentId, "razorpay");
        Merchant merchant = buildMerchant();

        when(transactionRepository.findByStateAndSourceGateway(
                TransactionState.PENALTY_ACCRUING, "razorpay"))
                .thenReturn(List.of(txn));
        when(merchantRepository.findByConnectedGateway("razorpay"))
                .thenReturn(List.of(merchant));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.PaymentStatus(paymentId, "failed"));
        when(razorpayApiClient.initiateRefund(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.RefundResult("rfnd_Test789", "processed"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        service.resolveRazorpayTransactions(OffsetDateTime.now());

        // Assert — state changed to RESOLVED_REFUNDED
        assertThat(txn.getState()).isEqualTo(TransactionState.RESOLVED_REFUNDED);
        assertThat(txn.getResolvedAt()).isNotNull();

        // Assert — refund was called
        verify(razorpayApiClient).initiateRefund(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId);

        // Assert — audit trail has refund ID
        ArgumentCaptor<StateTransition> stCaptor = ArgumentCaptor.forClass(StateTransition.class);
        verify(stateTransitionRepository).save(stCaptor.capture());
        StateTransition saved = stCaptor.getValue();
        assertThat(saved.getReason()).contains("via Razorpay API");
        assertThat(saved.getReason()).contains("rfnd_Test789");
    }

    // ── Test 3: Non-Razorpay transactions are skipped ─────────────

    @Test
    @DisplayName("Non-Razorpay transactions are not fetched from DB")
    void nonRazorpayTransactions_notProcessed() {
        when(transactionRepository.findByStateAndSourceGateway(
                TransactionState.PENALTY_ACCRUING, "razorpay"))
                .thenReturn(List.of());

        // Act
        service.resolveRazorpayTransactions(OffsetDateTime.now());

        // Assert — no API calls, no merchant lookup
        verify(razorpayApiClient, never()).fetchPaymentStatus(any(), any(), any());
        verify(merchantRepository, never()).findByConnectedGateway(any());
    }

    // ── Test 4: Razorpay API error is handled gracefully ──────────

    @Test
    @DisplayName("Razorpay API error → transaction stays in PENALTY_ACCRUING")
    void razorpayApiError_transactionStaysInPenaltyAccruing() {
        String paymentId = "pay_TestError999";
        Transaction txn = buildPenaltyTransaction(paymentId, "razorpay");
        Merchant merchant = buildMerchant();

        when(transactionRepository.findByStateAndSourceGateway(
                TransactionState.PENALTY_ACCRUING, "razorpay"))
                .thenReturn(List.of(txn));
        when(merchantRepository.findByConnectedGateway("razorpay"))
                .thenReturn(List.of(merchant));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenThrow(new RuntimeException("Razorpay API timeout"));

        // Act
        service.resolveRazorpayTransactions(OffsetDateTime.now());

        // Assert — state unchanged
        assertThat(txn.getState()).isEqualTo(TransactionState.PENALTY_ACCRUING);
        assertThat(txn.getResolvedAt()).isNull();

        // Assert — no state transition recorded
        verify(stateTransitionRepository, never()).save(any());
    }

    // ── Test 5: Razorpay status=refunded → RESOLVED_REFUNDED ────

    @Test
    @DisplayName("Razorpay already refunded → transitions to RESOLVED_REFUNDED without calling refund API")
    void razorpayAlreadyRefunded_transitionsWithoutRefundCall() {
        String paymentId = "pay_TestRefunded000";
        Transaction txn = buildPenaltyTransaction(paymentId, "razorpay");
        Merchant merchant = buildMerchant();

        when(transactionRepository.findByStateAndSourceGateway(
                TransactionState.PENALTY_ACCRUING, "razorpay"))
                .thenReturn(List.of(txn));
        when(merchantRepository.findByConnectedGateway("razorpay"))
                .thenReturn(List.of(merchant));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.PaymentStatus(paymentId, "refunded"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        service.resolveRazorpayTransactions(OffsetDateTime.now());

        // Assert — state changed to RESOLVED_REFUNDED
        assertThat(txn.getState()).isEqualTo(TransactionState.RESOLVED_REFUNDED);

        // Assert — no refund API call (already refunded)
        verify(razorpayApiClient, never()).initiateRefund(any(), any(), any());

        // Assert — reason mentions "already refunded"
        ArgumentCaptor<StateTransition> stCaptor = ArgumentCaptor.forClass(StateTransition.class);
        verify(stateTransitionRepository).save(stCaptor.capture());
        assertThat(stCaptor.getValue().getReason()).contains("already refunded");
    }
}
