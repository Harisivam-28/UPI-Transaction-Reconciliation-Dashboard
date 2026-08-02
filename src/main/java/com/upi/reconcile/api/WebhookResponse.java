package com.upi.reconcile.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for {@code POST /api/webhooks/transaction} — ARCHITECTURE.md §7.
 *
 * <pre>
 * → 200 { txn_id, state }  |  200 { txn_id, state: "DUPLICATE_IGNORED" }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookResponse {

    private UUID txnId;
    private String state;

    /** Sentinel state value returned when the idempotency key was already processed. */
    public static final String DUPLICATE_IGNORED = "DUPLICATE_IGNORED";
}
