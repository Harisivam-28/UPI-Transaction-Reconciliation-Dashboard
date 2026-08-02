package com.upi.reconcile.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed idempotency key cache.
 * <p>
 * Prevents duplicate webhook processing by checking if an idempotency key
 * has already been seen within a configurable TTL window.
 * <p>
 * TODO: Integrate with WebhookController for dedup flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Check if the key has been seen before.
     *
     * @param idempotencyKey the webhook idempotency key
     * @return true if this is a duplicate (key already exists)
     */
    public boolean isDuplicate(String idempotencyKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + idempotencyKey));
    }

    /**
     * Mark the key as processed.
     *
     * @param idempotencyKey the webhook idempotency key
     * @param txnId          the transaction ID assigned
     */
    public void markProcessed(String idempotencyKey, String txnId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, txnId, DEFAULT_TTL);
        log.debug("Marked idempotency key {} → txn {}", idempotencyKey, txnId);
    }

    /**
     * Retrieve the transaction ID cached for a previously-processed key.
     *
     * @param idempotencyKey the webhook idempotency key
     * @return the cached txn ID, or null if not in Redis
     */
    public String getExistingTxnId(String idempotencyKey) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
    }
}
