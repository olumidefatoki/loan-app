package com.mfb.lending.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the production profile with the mock core, a public actuator password,
 * or a secret copied from the local example file.
 */
@Component
@Profile("production")
public class ProductionGuard implements InitializingBean {

    private static final Set<String> FORBIDDEN = Set.of(
            "9980806e9e5ce1529b9cdfe08de26388d33b1dcbd3a936552a3ee00d1f8efd5a",
            "a0b5f71e32880a33908e54709abfb93c4e4c61b6248c384967c09ddef5eb2414",
            "422bf808b3343b53076ecdb7768924a49cf44b95315bb6949d0ea12cc5693d8f",
            "6a30a967b5ad0d436b9d4b51d2d37ee18ebc391ffeea05634ac6f7c021b98611",
            "8be5d113c0871ce1a122d68a7dff940ab7b09ad8dfcfd9a85aa5c1acc026279e",
            "35d254727bbf73cea2872bb176d2a7cabf4cb361d816478e634913d1a47230c6",
            "12f99d46f34998a4f07220fb5ccf792b6feee2ccb0898a19141f1959de923c4a",
            "8af5fc56c16b9f2dd0fae94d4469badb655eb40eb271bb96e30ac472a38c30f3",
            "f6b1421d4e6e05b4c1321ffbc023696ff3273b02c665590a3022c3738fe4280b",
            "954a48a204277d0d7672d05f9beaee889ed17ee9cf938080027bf1bf39b9c551");

    private final CoreBankingProperties coreBanking;
    private final SecurityProperties security;
    private final AuthProperties auth;

    public ProductionGuard(CoreBankingProperties coreBanking, SecurityProperties security, AuthProperties auth) {
        this.coreBanking = coreBanking;
        this.security = security;
        this.auth = auth;
    }

    @Override
    public void afterPropertiesSet() {
        coreBanking.validate();
        if (!coreBanking.http()) {
            throw new IllegalStateException("Production requires app.core-banking.provider=http");
        }
        requireSecret(coreBanking.getWebhook().getSecret(), "Production core banking webhook secret");
        requireSecret(security.getOpsPassword(), "Production ops password");
        requireSecret(auth.getJwt().getHmacSecret(), "Production JWT HMAC secret");
        requireSecret(auth.getEncryptionKey(), "Production data key");
        requireSecret(auth.getOtp().getPepper(), "Production OTP pepper");
        if (!security.getApiRateLimit().isEnabled() || security.getApiRateLimit().getMaxRequests() < 1) {
            throw new IllegalStateException("Production requires the API rate limit");
        }
        String bootstrapPassword = auth.getBootstrap().getPassword();
        if (bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            requireSecret(bootstrapPassword, "Production bootstrap password");
            requireSecret(auth.getBootstrap().getMfaSecret(), "Production bootstrap MFA secret");
        }
    }

    private static void requireSecret(String value, String name) {
        if (value == null || value.isBlank() || value.length() < 12 || FORBIDDEN.contains(sha256(value))) {
            throw new IllegalStateException(name + " is missing or is a development secret");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
