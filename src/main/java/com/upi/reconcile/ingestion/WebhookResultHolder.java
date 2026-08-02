package com.upi.reconcile.ingestion;

import com.upi.reconcile.api.WebhookResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-memory bridge between the REST controller (producer side) and the Kafka
 * consumer.
 *
 * <p>The controller registers a {@link CompletableFuture} keyed by
 * {@code idempotency_key} before publishing to Kafka. The consumer completes
 * the future once it finishes processing (dedup check + DB persist). The
 * controller then returns the result to the HTTP caller.
 *
 * <p>This design keeps all dedup + persistence logic inside the Kafka consumer
 * while honouring the synchronous API contract from ARCHITECTURE.md §7.
 */
@Component
public class WebhookResultHolder {

    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    private final ConcurrentMap<String, CompletableFuture<WebhookResponse>> pending =
            new ConcurrentHashMap<>();

    /**
     * Register a pending result for the given idempotency key.
     * Called by the REST controller before publishing to Kafka.
     *
     * @param idempotencyKey the webhook idempotency key
     * @return a future that will be completed by the Kafka consumer
     */
    public CompletableFuture<WebhookResponse> register(String idempotencyKey) {
        CompletableFuture<WebhookResponse> future = new CompletableFuture<>();
        pending.put(idempotencyKey, future);
        return future;
    }

    /**
     * Complete the pending future for the given key.
     * Called by the Kafka consumer after processing.
     *
     * @param idempotencyKey the webhook idempotency key
     * @param response       the processing result
     */
    public void complete(String idempotencyKey, WebhookResponse response) {
        CompletableFuture<WebhookResponse> future = pending.remove(idempotencyKey);
        if (future != null) {
            future.complete(response);
        }
    }

    /**
     * Complete the pending future exceptionally.
     *
     * @param idempotencyKey the webhook idempotency key
     * @param ex             the exception
     */
    public void completeExceptionally(String idempotencyKey, Throwable ex) {
        CompletableFuture<WebhookResponse> future = pending.remove(idempotencyKey);
        if (future != null) {
            future.completeExceptionally(ex);
        }
    }

    /**
     * Wait for a result with default timeout.
     *
     * @param future the future to wait on
     * @return the webhook response
     * @throws Exception if timeout or processing error
     */
    public WebhookResponse await(CompletableFuture<WebhookResponse> future) throws Exception {
        try {
            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Webhook processing timed out after "
                    + DEFAULT_TIMEOUT_SECONDS + "s", e);
        }
    }
}
