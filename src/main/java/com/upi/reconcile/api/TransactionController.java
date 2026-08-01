package com.upi.reconcile.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction query and action endpoints — ARCHITECTURE.md §7.
 * <p>
 * GET  /api/transactions?state=&bank_id=&page=
 * GET  /api/transactions/:txn_id/history
 * POST /api/transactions/:txn_id/generate-complaint
 * <p>
 * TODO: Implement query logic, audit trail retrieval, and complaint generation.
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTransactions(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID bankId,
            @RequestParam(defaultValue = "0") int page) {
        log.info("List transactions — state={}, bankId={}, page={}", state, bankId, page);
        // TODO: Implement filtered/paginated query
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/{txnId}/history")
    public ResponseEntity<List<Map<String, Object>>> getTransactionHistory(@PathVariable UUID txnId) {
        log.info("Get history for txn {}", txnId);
        // TODO: Return full state_transitions audit trail
        return ResponseEntity.ok(Collections.emptyList());
    }

    @PostMapping("/{txnId}/generate-complaint")
    public ResponseEntity<Map<String, String>> generateComplaint(@PathVariable UUID txnId) {
        log.info("Generate complaint for txn {}", txnId);
        // TODO: Template-based Ombudsman complaint generation (no LLM, per §7)
        return ResponseEntity.ok(Map.of("complaint", "STUB_NOT_IMPLEMENTED"));
    }
}
