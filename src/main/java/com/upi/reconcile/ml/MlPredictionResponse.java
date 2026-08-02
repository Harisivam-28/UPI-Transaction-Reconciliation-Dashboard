package com.upi.reconcile.ml;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Response DTO from the ML mismatch classification service.
 * <p>
 * Maps to the {@code /classify} endpoint response:
 * classification (predicted label), confidence (probability),
 * and per-feature importance scores.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlPredictionResponse {

    /** Predicted label: stuck_payment | wrong_amount | duplicate_charge | no_mismatch */
    @JsonProperty("classification")
    private String classification;

    /** Probability of the predicted class (0.0 - 1.0). */
    @JsonProperty("confidence")
    private double confidence;

    /** Per-feature importance scores from the trained model. */
    @JsonProperty("feature_importances")
    private Map<String, Double> featureImportances;
}
