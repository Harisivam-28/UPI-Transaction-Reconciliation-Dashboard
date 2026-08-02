package com.upi.reconcile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for {@code POST /api/webhooks/transaction} — ARCHITECTURE.md §7.
 *
 * <pre>
 * body: { idempotency_key, remitter_bank_id, beneficiary_bank_id,
 *         amount_inr, order_reference, decline_code? }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookRequest {

    @NotBlank(message = "idempotency_key is required")
    private String idempotencyKey;

    @NotNull(message = "remitter_bank_id is required")
    private UUID remitterBankId;

    @NotNull(message = "beneficiary_bank_id is required")
    private UUID beneficiaryBankId;

    @NotNull(message = "amount_inr is required")
    private BigDecimal amountInr;

    @NotBlank(message = "order_reference is required")
    private String orderReference;

    /** Optional — if present, maps to a BD or TD decline code. */
    private String declineCode;
}
