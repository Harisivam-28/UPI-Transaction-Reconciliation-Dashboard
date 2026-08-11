package com.upi.reconcile.connectors.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Merchant entity — maps to the {@code merchants} table.
 *
 * <p>Stores the merchant's connected payment gateway and AES-256-GCM
 * encrypted API credentials. The {@code webhook_secret} is used to
 * verify incoming webhook signatures from gateways.
 */
@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "connected_gateway")
    private String connectedGateway;

    @Column(name = "encrypted_api_key")
    private String encryptedApiKey;

    @Column(name = "encrypted_api_secret")
    private String encryptedApiSecret;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
