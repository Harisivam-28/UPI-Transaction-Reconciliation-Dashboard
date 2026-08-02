package com.upi.reconcile.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Response DTO for {@code GET /api/transactions/{txnId}/history} — ARCHITECTURE.md §7.
 * <p>
 * Each entry represents one row from the immutable {@code state_transitions} audit log.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StateTransitionDto {

    private String fromState;
    private String toState;
    private OffsetDateTime transitionedAt;
    private String reason;
}
