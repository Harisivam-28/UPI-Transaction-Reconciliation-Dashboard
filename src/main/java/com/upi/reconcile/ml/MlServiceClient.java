package com.upi.reconcile.ml;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * HTTP client for the Python FastAPI mismatch classifier service.
 * <p>
 * Calls POST {@code {ml-service.base-url}/classify} with transaction features
 * and returns a classification prediction with confidence score.
 * <p>
 * Includes a 2-second timeout and graceful fallback if the ML service
 * is unavailable — transaction processing is never blocked by ML failures.
 */
@Slf4j
@Component
public class MlServiceClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public MlServiceClient(@Value("${ml-service.base-url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Call the ML service /classify endpoint.
     *
     * @param request classification request with 6 transaction features
     * @return classification response, or {@code null} if the service is unreachable
     */
    public MlPredictionResponse classify(MlPredictionRequest request) {
        try {
            log.debug("Calling ML service for classification: {}", request);
            return webClient.post()
                    .uri("/classify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(MlPredictionResponse.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(ex -> {
                        log.warn("ML service call failed (non-blocking): {}", ex.getMessage());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.warn("ML service unavailable — skipping classification: {}", e.getMessage());
            return null;
        }
    }
}
