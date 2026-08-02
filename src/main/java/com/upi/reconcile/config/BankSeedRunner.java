package com.upi.reconcile.config;

import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.BankRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the {@code banks} table on application startup.
 *
 * <p><b>Data source priority:</b></p>
 * <ol>
 *   <li>CSV file at {@code classpath:seed/npci_bank_stats.csv} (real NPCI data)</li>
 *   <li>Hardcoded placeholder banks if CSV is absent</li>
 * </ol>
 *
 * <p><b>Idempotency:</b> If the table already contains rows, the runner skips entirely.
 * This means the seed runs exactly once — on first boot against a fresh database.</p>
 */
@Component
public class BankSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BankSeedRunner.class);

    private static final String CSV_PATH = "seed/npci_bank_stats.csv";

    /**
     * Stable namespace UUID for generating deterministic bank IDs via UUID v5.
     * This ensures the same bank name always maps to the same UUID across environments.
     */
    private static final UUID NAMESPACE_BANKS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    private final BankRepository bankRepository;

    public BankSeedRunner(BankRepository bankRepository) {
        this.bankRepository = bankRepository;
    }

    @Override
    public void run(String... args) {
        if (bankRepository.count() > 0) {
            log.info("Banks table already seeded ({} rows) — skipping.", bankRepository.count());
            return;
        }

        List<Bank> banks;
        try {
            banks = loadFromCsv();
            log.info("Loaded {} banks from NPCI CSV ({})", banks.size(), CSV_PATH);
        } catch (Exception e) {
            log.warn("CSV not found or unreadable at classpath:{}. Falling back to placeholder data.", CSV_PATH);
            banks = placeholderBanks();
            log.info("Seeded {} PLACEHOLDER banks — replace with real NPCI data before demo!", banks.size());
        }

        bankRepository.saveAll(banks);
        log.info("Banks table seeded successfully with {} entries.", banks.size());
    }

    // ── CSV Loading ──────────────────────────────────────────────────────────────

    private List<Bank> loadFromCsv() throws Exception {
        ClassPathResource resource = new ClassPathResource(CSV_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("CSV resource does not exist");
        }

        List<Bank> banks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine(); // skip header row
            if (headerLine == null) {
                throw new IllegalStateException("CSV file is empty");
            }

            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // CSV format: bank_name, historical_bd_rate, historical_td_rate,
                //             historical_deemed_approved_rate, source_note
                // source_note may contain commas inside quotes, so we split carefully.
                String[] parts = parseCsvLine(trimmed);
                if (parts.length < 4) {
                    log.warn("Skipping malformed CSV line {}: '{}'", lineNum, trimmed);
                    continue;
                }

                String bankName = parts[0].trim();
                BigDecimal bdRate = new BigDecimal(parts[1].trim());
                BigDecimal tdRate = new BigDecimal(parts[2].trim());
                BigDecimal deemedApprovedRate = new BigDecimal(parts[3].trim());

                // Log source_note if present (column 5) — not persisted to DB
                if (parts.length >= 5 && !parts[4].trim().isEmpty()) {
                    log.info("  {} — source: {}", bankName, parts[4].trim());
                }

                banks.add(Bank.builder()
                        .bankId(deterministicUuid(bankName))
                        .name(bankName)
                        .historicalBdRate(bdRate)
                        .historicalTdRate(tdRate)
                        .historicalDeemedApprovedRate(deemedApprovedRate)
                        .build());
            }
        }

        if (banks.isEmpty()) {
            throw new IllegalStateException("CSV contained no valid data rows");
        }
        return banks;
    }

    // ── Placeholder Fallback ─────────────────────────────────────────────────────

    /**
     * 8 realistic placeholder banks with rates in the 0.03%–1.1% range.
     * PLACEHOLDER — replace with real NPCI dataset before the demo!
     */
    private List<Bank> placeholderBanks() {
        List<Bank> banks = new ArrayList<>();

        // PLACEHOLDER: All rates below are illustrative. Replace with real NPCI data.
        banks.add(placeholder("State Bank of India",        "0.0045", "0.0008", "0.0012")); // PLACEHOLDER
        banks.add(placeholder("HDFC Bank Ltd.",             "0.0058", "0.0009", "0.0012")); // PLACEHOLDER
        banks.add(placeholder("ICICI Bank Ltd.",            "0.0071", "0.0009", "0.0012")); // PLACEHOLDER
        banks.add(placeholder("Axis Bank Ltd.",             "0.0074", "0.0010", "0.0015")); // PLACEHOLDER
        banks.add(placeholder("Paytm Payments Bank Ltd.",   "0.0110", "0.0012", "0.0018")); // PLACEHOLDER
        banks.add(placeholder("Yes Bank Ltd.",              "0.0065", "0.0011", "0.0014")); // PLACEHOLDER
        banks.add(placeholder("Kotak Mahindra Bank",        "0.0089", "0.0008", "0.0012")); // PLACEHOLDER
        banks.add(placeholder("Punjab National Bank",       "0.0038", "0.0007", "0.0010")); // PLACEHOLDER

        return banks;
    }

    private Bank placeholder(String name, String bdRate, String tdRate, String deemedRate) {
        return Bank.builder()
                .bankId(deterministicUuid(name))
                .name(name)
                .historicalBdRate(new BigDecimal(bdRate))
                .historicalTdRate(new BigDecimal(tdRate))
                .historicalDeemedApprovedRate(new BigDecimal(deemedRate))
                .build();
    }

    // ── Utilities ────────────────────────────────────────────────────────────────

    /**
     * Generates a deterministic UUID v5 (name-based) from the bank name.
     * Same input always produces the same UUID across environments/runs.
     */
    static UUID deterministicUuid(String bankName) {
        return UUID.nameUUIDFromBytes(
                (NAMESPACE_BANKS.toString() + ":" + bankName).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal CSV parser that handles quoted fields (for source_note with commas).
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString()); // last field

        return fields.toArray(new String[0]);
    }
}
