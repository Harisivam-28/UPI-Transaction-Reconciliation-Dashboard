package com.upi.reconcile.connectors.api;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Merchant onboarding endpoint.
 *
 * <pre>
 * POST /api/merchants/connect
 *   body: { gateway, api_key, api_secret, name? }
 *   → 200 { merchant_id, webhook_url, webhook_secret }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private static final Set<String> SUPPORTED_GATEWAYS = Set.of("razorpay", "payu", "cashfree");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MerchantRepository merchantRepository;
    private final AesGcmEncryptor encryptor;

    @PostMapping("/connect")
    public ResponseEntity<MerchantConnectResponse> connectMerchant(
            @Valid @RequestBody MerchantConnectRequest request) {

        String gateway = request.getGateway().toLowerCase().trim();
        if (!SUPPORTED_GATEWAYS.contains(gateway)) {
            return ResponseEntity.badRequest().build();
        }

        UUID merchantId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Merchant merchant = merchantRepository.findById(merchantId).orElseThrow();

        // If the user passes a name, update the businessName (optional based on old behavior)
        if (request.getName() != null && !request.getName().isBlank()) {
            merchant.setBusinessName(request.getName());
        }

        // Encrypt credentials before storing
        String encryptedApiKey = encryptor.encrypt(request.getApiKey());
        String encryptedApiSecret = encryptor.encrypt(request.getApiSecret());

        // Generate a cryptographically secure 32-byte webhook secret
        byte[] secretBytes = new byte[32];
        SECURE_RANDOM.nextBytes(secretBytes);
        String webhookSecret = HexFormat.of().formatHex(secretBytes);

        merchant.setConnectedGateway(gateway);
        merchant.setEncryptedApiKey(encryptedApiKey);
        merchant.setEncryptedApiSecret(encryptedApiSecret);
        merchant.setWebhookSecret(webhookSecret);
        
        merchantRepository.save(merchant);

        String webhookUrl = String.format("/api/connectors/%s/webhook?merchant_id=%s",
                gateway, merchantId);

        log.info("Merchant {} connected to gateway {}, webhook_url={}",
                merchantId, gateway, webhookUrl);

        MerchantConnectResponse response = MerchantConnectResponse.builder()
                .merchantId(merchantId)
                .webhookUrl(webhookUrl)
                .webhookSecret(webhookSecret)
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/connected-gateways")
    public ResponseEntity<List<String>> getConnectedGateways() {
        List<String> gateways = merchantRepository.findAll().stream()
                .filter(m -> m.getConnectedGateway() != null)
                .map(Merchant::getConnectedGateway)
                .distinct()
                .toList();
        return ResponseEntity.ok(gateways);
    }
}

