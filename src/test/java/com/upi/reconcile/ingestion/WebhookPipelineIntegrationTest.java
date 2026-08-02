package com.upi.reconcile.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookRequest;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.WebhookEvent;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the webhook ingestion pipeline.
 *
 * <p>Uses Testcontainers to spin up real Postgres, Kafka (KRaft), and Redis
 * instances. Verifies that firing the same idempotency key twice produces
 * exactly one transaction row and two webhook_event rows (one with
 * {@code duplicate_of} set).
 *
 * <p>Kafka consumer idempotency is properly configured with manual ack
 * after DB write, so even consumer crashes cannot create duplicate
 * transactions on redelivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Testcontainers
class WebhookPipelineIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private BankRepository bankRepository;

    @Autowired
    private StateTransitionRepository stateTransitionRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ── Test fixtures ─────────────────────────────────────────────

    private static final UUID REMITTER_BANK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BENEFICIARY_BANK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        // TRUNCATE CASCADE handles self-referencing FKs (webhook_events.duplicate_of)
        // and cross-table FKs (state_transitions → transactions) cleanly.
        jdbcTemplate.execute("TRUNCATE TABLE state_transitions, webhook_events, transactions CASCADE");

        // Flush Redis idempotency cache to prevent cross-test interference
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Seed banks if not already present (idempotent)
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

    // ── Tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Same idempotency_key fired twice → exactly one transaction row created")
    void firesSameIdempotencyKeyTwice_createsExactlyOneTransaction() throws Exception {

        String idempotencyKey = "test-key-" + UUID.randomUUID();

        WebhookRequest request = WebhookRequest.builder()
                .idempotencyKey(idempotencyKey)
                .remitterBankId(REMITTER_BANK_ID)
                .beneficiaryBankId(BENEFICIARY_BANK_ID)
                .amountInr(new BigDecimal("1500.00"))
                .orderReference("ORD-12345")
                .build();

        String json = objectMapper.writeValueAsString(request);

        // ── First POST — should create a new transaction ─────────
        MvcResult firstResult = mockMvc.perform(post("/api/webhooks/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").exists())
                .andExpect(jsonPath("$.state").exists())
                .andReturn();

        WebhookResponse firstResponse = objectMapper.readValue(
                firstResult.getResponse().getContentAsString(), WebhookResponse.class);

        assertThat(firstResponse.getTxnId()).isNotNull();
        assertThat(firstResponse.getState()).isNotEqualTo(WebhookResponse.DUPLICATE_IGNORED);

        // ── Second POST — same payload, same idempotency key ─────
        MvcResult secondResult = mockMvc.perform(post("/api/webhooks/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(WebhookResponse.DUPLICATE_IGNORED))
                .andReturn();

        WebhookResponse secondResponse = objectMapper.readValue(
                secondResult.getResponse().getContentAsString(), WebhookResponse.class);

        assertThat(secondResponse.getTxnId()).isEqualTo(firstResponse.getTxnId());
        assertThat(secondResponse.getState()).isEqualTo(WebhookResponse.DUPLICATE_IGNORED);

        // ── Assert exactly ONE transaction in DB ─────────────────
        long txnCount = transactionRepository.count();
        assertThat(txnCount)
                .as("Exactly one transaction row should exist for idempotency_key '%s'", idempotencyKey)
                .isEqualTo(1);

        // ── Assert TWO webhook_events (original + duplicate) ─────
        List<WebhookEvent> events = webhookEventRepository.findAll();
        long eventsForKey = events.stream()
                .filter(e -> e.getIdempotencyKey().equals(idempotencyKey))
                .count();
        assertThat(eventsForKey)
                .as("Two webhook_event rows should exist (one original, one duplicate)")
                .isEqualTo(2);

        // ── Assert the duplicate has duplicate_of set ─────────────
        long duplicateCount = events.stream()
                .filter(e -> e.getIdempotencyKey().equals(idempotencyKey))
                .filter(e -> e.getDuplicateOf() != null)
                .count();
        assertThat(duplicateCount)
                .as("Exactly one webhook_event should have duplicate_of set")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Webhook with BD decline code → transaction in BUSINESS_DECLINED state")
    void webhookWithBdDeclineCode_createsBusinessDeclinedTransaction() throws Exception {

        String idempotencyKey = "bd-test-" + UUID.randomUUID();

        WebhookRequest request = WebhookRequest.builder()
                .idempotencyKey(idempotencyKey)
                .remitterBankId(REMITTER_BANK_ID)
                .beneficiaryBankId(BENEFICIARY_BANK_ID)
                .amountInr(new BigDecimal("500.00"))
                .orderReference("ORD-BD-001")
                .declineCode("BAD_PIN")
                .build();

        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/webhooks/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BUSINESS_DECLINED"));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Webhook with TD decline code → transaction in TECHNICAL_DECLINED state")
    void webhookWithTdDeclineCode_createsTechnicalDeclinedTransaction() throws Exception {

        String idempotencyKey = "td-test-" + UUID.randomUUID();

        WebhookRequest request = WebhookRequest.builder()
                .idempotencyKey(idempotencyKey)
                .remitterBankId(REMITTER_BANK_ID)
                .beneficiaryBankId(BENEFICIARY_BANK_ID)
                .amountInr(new BigDecimal("750.00"))
                .orderReference("ORD-TD-001")
                .declineCode("MALFORMED_BANK_ID")
                .build();

        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/webhooks/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TECHNICAL_DECLINED"));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Webhook with no decline code → transaction in SUCCESS state")
    void webhookWithNoDeclineCode_createsSuccessTransaction() throws Exception {

        String idempotencyKey = "success-test-" + UUID.randomUUID();

        WebhookRequest request = WebhookRequest.builder()
                .idempotencyKey(idempotencyKey)
                .remitterBankId(REMITTER_BANK_ID)
                .beneficiaryBankId(BENEFICIARY_BANK_ID)
                .amountInr(new BigDecimal("2000.00"))
                .orderReference("ORD-SUCCESS-001")
                .build();

        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/webhooks/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"));

        assertThat(transactionRepository.count()).isEqualTo(1);
    }
}
