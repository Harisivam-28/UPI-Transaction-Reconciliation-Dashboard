package com.upi.reconcile.connectors.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signature verifier for webhook payload authentication.
 *
 * <p>Razorpay signs each webhook POST body with HMAC-SHA256 using the
 * webhook secret configured in the merchant's dashboard. The signature
 * is sent in the {@code X-Razorpay-Signature} header as a lowercase
 * hex string.
 *
 * <p>Verification uses constant-time comparison to prevent timing attacks.
 *
 * @see <a href="https://razorpay.com/docs/webhooks/validate-test/">Razorpay docs — Validate webhooks</a>
 */
public final class HmacSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private HmacSignatureVerifier() {
        // utility class
    }

    /**
     * Computes HMAC-SHA256 of the given payload using the provided secret,
     * returning the result as a lowercase hex string.
     *
     * @param payload the raw webhook body
     * @param secret  the webhook secret (shared between gateway and us)
     * @return lowercase hex-encoded HMAC-SHA256 digest
     */
    public static String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * Verifies that the provided signature matches the HMAC-SHA256 of the
     * payload, using constant-time comparison to prevent timing attacks.
     *
     * @param payload           the raw webhook body
     * @param secret            the webhook secret
     * @param providedSignature the signature from the gateway's HTTP header
     * @return true if the signature is valid
     */
    public static boolean verify(String payload, String secret, String providedSignature) {
        String expected = computeHmac(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }
}
