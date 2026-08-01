package com.upi.reconcile.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook ingestion endpoint — ARCHITECTURE.md §7.
 * <p>
 * POST /api/webhooks/transaction
 *   body: { idempotency_key, remitter_bank_id, beneficiary_bank_id, amount_inr, order_reference, decline_code? }
 *   → 200 { txn_id, state }  |  200 { txn_id, state: "DUPLICATE_IGNORED" }
 * <p>
 * TODO: Implement idempotency check (Redis) + state machine invocation.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    @PostMapping("/transaction")
    public ResponseEntity<Map<String, Object>> receiveTransaction(@RequestBody Map<String, Object> payload) {
        log.info("Received webhook: {}", payload);
        // TODO: Idempotency check via Redis, persist WebhookEvent, create Transaction, run state machine
        return ResponseEntity.ok(Map.of("status", "STUB_NOT_IMPLEMENTED"));
    }
}
