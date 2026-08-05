package com.upi.reconcile.connectors.api;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Merchant onboarding endpoint.
 *
 * <pre>
 * POST /api/merchants/connect
 *   body: { gateway, api_key, api_secret, name? }
 *   → 200 { merchant_id, webhook_url }
 * </pre>
 *
 * <p>Encrypts the merchant's API key and secret using AES-256-GCM before
 * persisting to the {@code merchants} table, then returns a webhook URL
 * the merchant should register with their payment gateway dashboard.
 */
@Slf4j
@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private static final Set<String> SUPPORTED_GATEWAYS = Set.of("razorpay", "payu", "cashfree");

    private final MerchantRepository merchantRepository;
    private final AesGcmEncryptor encryptor;

    @PostMapping("/connect")
    public ResponseEntity<MerchantConnectResponse> connectMerchant(
            @Valid @RequestBody MerchantConnectRequest request) {

        String gateway = request.getGateway().toLowerCase().trim();
        if (!SUPPORTED_GATEWAYS.contains(gateway)) {
            return ResponseEntity.badRequest().build();
        }

        UUID merchantId = UUID.randomUUID();
        String name = (request.getName() != null && !request.getName().isBlank())
                ? request.getName()
                : "Merchant-" + merchantId.toString().substring(0, 8);

        // Encrypt credentials before storing
        String encryptedApiKey = encryptor.encrypt(request.getApiKey());
        String encryptedApiSecret = encryptor.encrypt(request.getApiSecret());

        Merchant merchant = Merchant.builder()
                .merchantId(merchantId)
                .name(name)
                .connectedGateway(gateway)
                .encryptedApiKey(encryptedApiKey)
                .encryptedApiSecret(encryptedApiSecret)
                .createdAt(OffsetDateTime.now())
                .build();
        merchantRepository.save(merchant);

        String webhookUrl = String.format("/api/connectors/%s/webhook?merchant_id=%s",
                gateway, merchantId);

        log.info("Merchant connected — id={}, gateway={}, webhook_url={}",
                merchantId, gateway, webhookUrl);

        MerchantConnectResponse response = MerchantConnectResponse.builder()
                .merchantId(merchantId)
                .webhookUrl(webhookUrl)
                .build();

        return ResponseEntity.ok(response);
    }
}
