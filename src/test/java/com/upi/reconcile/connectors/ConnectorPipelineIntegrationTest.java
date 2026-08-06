package com.upi.reconcile.connectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.connectors.crypto.HmacSignatureVerifier;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the payment gateway connectors pipeline.
 *
 * <p>Feeds real-shaped Razorpay test payloads and stub PayU/Cashfree payloads
 * through the full pipeline (Connector → Kafka → Consumer → StateMachine → DB)
 * and verifies that transaction records are created correctly.
 *
 * <p>Uses Testcontainers for Postgres, Kafka (KRaft), and Redis — same setup
 * as {@link com.upi.reconcile.ingestion.WebhookPipelineIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Testcontainers
class ConnectorPipelineIntegrationTest {

    // ── Testcontainers ────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("upi_reconcile_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Postgres
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ── Autowired beans ───────────────────────────────────────────

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private BankRepository bankRepository;
    @Autowired private StateTransitionRepository stateTransitionRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ── Test fixtures ─────────────────────────────────────────────

    private static final UUID REMITTER_BANK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BENEFICIARY_BANK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TEST_WEBHOOK_SECRET = "whsec_test_secret_for_hmac_verification";

    @BeforeEach
    void setUp() {
        // Clean all tables
        jdbcTemplate.execute("TRUNCATE TABLE state_transitions, webhook_events, transactions, merchants CASCADE");

        // Flush Redis
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Seed banks if not present
        if (bankRepository.findById(REMITTER_BANK_ID).isEmpty()) {
            bankRepository.save(Bank.builder()
                    .bankId(REMITTER_BANK_ID)
                    .name("Test Remitter Bank")
                    .historicalBdRate(new BigDecimal("0.0200"))
                    .historicalTdRate(new BigDecimal("0.0100"))
                    .historicalDeemedApprovedRate(new BigDecimal("0.0050"))
                    .build());
        }
        if (bankRepository.findById(BENEFICIARY_BANK_ID).isEmpty()) {
            bankRepository.save(Bank.builder()
                    .bankId(BENEFICIARY_BANK_ID)
                    .name("Test Beneficiary Bank")
                    .historicalBdRate(new BigDecimal("0.0150"))
                    .historicalTdRate(new BigDecimal("0.0080"))
                    .historicalDeemedApprovedRate(new BigDecimal("0.0040"))
                    .build());
        }
    }

    /**
     * Creates a test merchant with a webhook secret for Razorpay signature tests.
     */
    private UUID createTestMerchant() {
        UUID merchantId = UUID.randomUUID();
        merchantRepository.save(Merchant.builder()
                .merchantId(merchantId)
                .name("Test Merchant")
                .connectedGateway("razorpay")
                .encryptedApiKey("encrypted_key")
                .encryptedApiSecret("encrypted_secret")
                .webhookSecret(TEST_WEBHOOK_SECRET)
                .createdAt(OffsetDateTime.now())
                .build());
        return merchantId;
    }

    // ═══════════════════════════════════════════════════════════════
    // Razorpay — payment.captured (SUCCESS)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Razorpay payment.captured webhook with valid HMAC → transaction in SUCCESS state")
    void razorpayPaymentCaptured_createsSuccessTransaction() throws Exception {

        UUID merchantId = createTestMerchant();

        // Build a real-shaped Razorpay test-mode webhook payload
        String paymentId = "pay_TEST" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        String orderId = "order_TEST" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        Map<String, Object> razorpayPayload = buildRazorpayPayload(
                "payment.captured",
                paymentId,
                50000,      // 500.00 INR in paise
                "captured",
                orderId,
                null,       // no error_code
                null        // no error_description
        );

        String json = objectMapper.writeValueAsString(razorpayPayload);
        String signature = HmacSignatureVerifier.computeHmac(json, TEST_WEBHOOK_SECRET);

        // POST to the Razorpay connector endpoint with valid signature
        MvcResult result = mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .header("X-Razorpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").exists())
                .andExpect(jsonPath("$.state").value("SUCCESS"))
                .andReturn();

        WebhookResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), WebhookResponse.class);

        // Verify transaction was created
        assertThat(response.getTxnId()).isNotNull();
        assertThat(transactionRepository.count()).isEqualTo(1);

        Transaction txn = transactionRepository.findById(response.getTxnId()).orElseThrow();
        assertThat(txn.getIdempotencyKey()).isEqualTo(paymentId);
        assertThat(txn.getAmountInr()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(txn.getOrderReference()).isEqualTo(orderId);
        assertThat(txn.getState().name()).isEqualTo("SUCCESS");
        assertThat(txn.getDeclineCode()).isNull();
    }

    // ═══════════════════════════════════════════════════════════════
    // Razorpay — payment.failed (BUSINESS_DECLINED)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Razorpay payment.failed with BAD_REQUEST_ERROR + valid HMAC → BUSINESS_DECLINED")
    void razorpayPaymentFailed_bdError_createsBusinessDeclinedTransaction() throws Exception {

        UUID merchantId = createTestMerchant();
        String paymentId = "pay_FAIL" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);

        Map<String, Object> razorpayPayload = buildRazorpayPayload(
                "payment.failed",
                paymentId,
                75000,      // 750.00 INR
                "failed",
                "order_FAIL001",
                "BAD_REQUEST_ERROR",
                "The payment was declined by the bank"
        );

        String json = objectMapper.writeValueAsString(razorpayPayload);
        String signature = HmacSignatureVerifier.computeHmac(json, TEST_WEBHOOK_SECRET);

        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .header("X-Razorpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSINESS_DECLINED"));

        assertThat(transactionRepository.count()).isEqualTo(1);

        Transaction txn = transactionRepository.findAll().get(0);
        assertThat(txn.getIdempotencyKey()).isEqualTo(paymentId);
        assertThat(txn.getDeclineCode()).isEqualTo("BAD_PIN");
    }

    // ═══════════════════════════════════════════════════════════════
    // Razorpay — payment.failed (TECHNICAL_DECLINED)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Razorpay payment.failed with GATEWAY_ERROR + valid HMAC → TECHNICAL_DECLINED")
    void razorpayPaymentFailed_tdError_createsTechnicalDeclinedTransaction() throws Exception {

        UUID merchantId = createTestMerchant();
        String paymentId = "pay_GWERR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Map<String, Object> razorpayPayload = buildRazorpayPayload(
                "payment.failed",
                paymentId,
                100000,     // 1000.00 INR
                "failed",
                "order_GWERR001",
                "GATEWAY_ERROR",
                "Gateway request timed out"
        );

        String json = objectMapper.writeValueAsString(razorpayPayload);
        String signature = HmacSignatureVerifier.computeHmac(json, TEST_WEBHOOK_SECRET);

        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .header("X-Razorpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TECHNICAL_DECLINED"));

        assertThat(transactionRepository.count()).isEqualTo(1);

        Transaction txn = transactionRepository.findAll().get(0);
        assertThat(txn.getIdempotencyKey()).isEqualTo(paymentId);
        assertThat(txn.getDeclineCode()).isEqualTo("MALFORMED_BANK_ID");
    }

    // ═══════════════════════════════════════════════════════════════
    // Razorpay — HMAC signature rejection tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Razorpay webhook without X-Razorpay-Signature → 401 Unauthorized")
    void razorpayWebhook_missingSignature_returns401() throws Exception {

        UUID merchantId = createTestMerchant();

        Map<String, Object> razorpayPayload = buildRazorpayPayload(
                "payment.captured", "pay_NOSIG001", 10000, "captured",
                "order_NOSIG", null, null);
        String json = objectMapper.writeValueAsString(razorpayPayload);

        // No X-Razorpay-Signature header → should be rejected
        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());

        // No transaction should have been created
        assertThat(transactionRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Razorpay webhook with wrong signature → 401 Unauthorized")
    void razorpayWebhook_wrongSignature_returns401() throws Exception {

        UUID merchantId = createTestMerchant();

        Map<String, Object> razorpayPayload = buildRazorpayPayload(
                "payment.captured", "pay_BADSIG001", 10000, "captured",
                "order_BADSIG", null, null);
        String json = objectMapper.writeValueAsString(razorpayPayload);

        // Compute HMAC with a wrong secret → should be rejected
        String wrongSignature = HmacSignatureVerifier.computeHmac(json, "wrong_secret_entirely");

        mockMvc.perform(post("/api/connectors/razorpay/webhook")
                        .param("merchant_id", merchantId.toString())
                        .header("X-Razorpay-Signature", wrongSignature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());

        // No transaction should have been created
        assertThat(transactionRepository.count()).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════════════
    // PayU stub — success
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PayU stub webhook (success) → transaction in SUCCESS state")
    void payuStubWebhook_success_createsSuccessTransaction() throws Exception {

        Map<String, Object> payuPayload = new LinkedHashMap<>();
        payuPayload.put("txn_id", "PAYU-TXN-" + UUID.randomUUID().toString().substring(0, 8));
        payuPayload.put("amount", "300.00");
        payuPayload.put("status", "success");

        String json = objectMapper.writeValueAsString(payuPayload);

        mockMvc.perform(post("/api/connectors/payu/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Cashfree stub — success
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Cashfree stub webhook (PAID) → transaction in SUCCESS state")
    void cashfreeStubWebhook_paid_createsSuccessTransaction() throws Exception {

        Map<String, Object> cashfreePayload = new LinkedHashMap<>();
        cashfreePayload.put("cf_order_id", "CF-ORDER-" + UUID.randomUUID().toString().substring(0, 8));
        cashfreePayload.put("order_amount", "450.00");
        cashfreePayload.put("order_status", "PAID");

        String json = objectMapper.writeValueAsString(cashfreePayload);

        mockMvc.perform(post("/api/connectors/cashfree/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Merchant connect
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("POST /api/merchants/connect stores encrypted credentials and returns webhook_url + webhook_secret")
    void merchantConnect_storesEncryptedCredentials() throws Exception {

        Map<String, Object> connectRequest = new LinkedHashMap<>();
        connectRequest.put("gateway", "razorpay");
        connectRequest.put("apiKey", "rzp_test_1234567890");
        connectRequest.put("apiSecret", "secret_test_abcdef");
        connectRequest.put("name", "Test Merchant");

        String json = objectMapper.writeValueAsString(connectRequest);

        MvcResult result = mockMvc.perform(post("/api/merchants/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").exists())
                .andExpect(jsonPath("$.webhookUrl").exists())
                .andExpect(jsonPath("$.webhookSecret").exists())
                .andReturn();

        // Verify webhook_secret is a 64-character hex string (32 bytes)
        String responseBody = result.getResponse().getContentAsString();
        var responseMap = objectMapper.readValue(responseBody, Map.class);
        String webhookSecret = (String) responseMap.get("webhookSecret");
        assertThat(webhookSecret).hasSize(64);
        assertThat(webhookSecret).matches("[0-9a-f]{64}");

        // Verify merchant was persisted
        assertThat(merchantRepository.count()).isEqualTo(1);

        // Verify credentials are encrypted (not plaintext)
        var merchant = merchantRepository.findAll().get(0);
        assertThat(merchant.getEncryptedApiKey()).isNotEqualTo("rzp_test_1234567890");
        assertThat(merchant.getEncryptedApiSecret()).isNotEqualTo("secret_test_abcdef");
        assertThat(merchant.getConnectedGateway()).isEqualTo("razorpay");
        assertThat(merchant.getName()).isEqualTo("Test Merchant");

        // Verify webhook_secret was stored on the entity and matches the response
        assertThat(merchant.getWebhookSecret()).isEqualTo(webhookSecret);
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper — build Razorpay payload
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds a real-shaped Razorpay test-mode webhook payload.
     * Matches the structure documented at:
     * https://razorpay.com/docs/webhooks/payloads/payments/
     */
    private Map<String, Object> buildRazorpayPayload(String event,
                                                      String paymentId,
                                                      int amountPaise,
                                                      String status,
                                                      String orderId,
                                                      String errorCode,
                                                      String errorDescription) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", paymentId);
        entity.put("entity", "payment");
        entity.put("amount", amountPaise);
        entity.put("currency", "INR");
        entity.put("status", status);
        entity.put("order_id", orderId);
        entity.put("method", "upi");
        entity.put("description", "Test payment");
        entity.put("error_code", errorCode);
        entity.put("error_description", errorDescription);
        entity.put("error_source", errorCode != null ? "bank" : null);
        entity.put("error_step", errorCode != null ? "payment_authorization" : null);
        entity.put("error_reason", errorCode != null ? "payment_failed" : null);

        Map<String, Object> paymentWrapper = new LinkedHashMap<>();
        paymentWrapper.put("entity", entity);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment", paymentWrapper);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", "event");
        root.put("account_id", "acc_TEST123456");
        root.put("event", event);
        root.put("contains", new String[]{"payment"});
        root.put("payload", payload);
        root.put("created_at", System.currentTimeMillis() / 1000);

        return root;
    }
}
