package com.upi.reconcile.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO from the ML classification service.
 * <p>
 * TODO: Align fields with actual model output.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlPredictionResponse {

    private String predictedState;
    private double confidence;
}
