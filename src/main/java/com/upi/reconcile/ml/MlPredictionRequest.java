package com.upi.reconcile.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for the ML classification service.
 * <p>
 * TODO: Define actual feature set based on decision-tree model requirements.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlPredictionRequest {

    private String remitterBankId;
    private String beneficiaryBankId;
    private BigDecimal amountInr;
    private String declineCode;
    private String currentState;
}
