package com.upi.reconcile.ml;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HTTP client for the Python FastAPI decision-tree classifier service.
 * <p>
 * Calls POST {ml-service.base-url}/predict with transaction features
 * and returns a classification prediction.
 * <p>
 * TODO: Implement typed request/response mapping.
 */
@Slf4j
@Component
public class MlServiceClient {

    private final WebClient webClient;

    public MlServiceClient(@Value("${ml-service.base-url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Call the ML service /predict endpoint.
     *
     * @param request prediction request with transaction features
     * @return prediction response with classification result
     */
    public MlPredictionResponse predict(MlPredictionRequest request) {
        log.info("Calling ML service for prediction");
        return webClient.post()
                .uri("/predict")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MlPredictionResponse.class)
                .block();
    }
}
