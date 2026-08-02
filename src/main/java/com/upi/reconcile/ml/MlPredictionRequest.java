package com.upi.reconcile.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for the ML mismatch classification service.
 * <p>
 * Maps to the 6 features used by the trained DecisionTreeClassifier:
 * amount_expected, amount_actual, time_pending_seconds,
 * decline_code_category (BD/TD/none),
 * remitter_bank_historical_td_rate, is_duplicate_flag.
 * <p>
 * Uses {@code @JsonProperty} with snake_case to match the Python FastAPI schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlPredictionRequest {

    @JsonProperty("amount_expected")
    private BigDecimal amountExpected;

    @JsonProperty("amount_actual")
    private BigDecimal amountActual;

    @JsonProperty("amount_diff_pct")
    private double amountDiffPct;

    @JsonProperty("time_pending_seconds")
    private long timePendingSeconds;

    @JsonProperty("decline_code_category")
    private String declineCodeCategory;

    @JsonProperty("remitter_bank_historical_td_rate")
    private double remitterBankHistoricalTdRate;

    @JsonProperty("is_duplicate_flag")
    private boolean isDuplicateFlag;
}
