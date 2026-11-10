package com.mfb.lending.module.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.mfb.lending.module.auth.domain.OtpPurpose;

public final class OtpHasher {

    private final byte[] pepper;

    public OtpHasher(String pepper) {
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(UUID userId, OtpPurpose purpose, String code) {
        return HexFormat.of().formatHex(mac(userId, purpose, code));
    }

    public boolean matches(UUID userId, OtpPurpose purpose, String code, String expectedHex) {
        return MessageDigest.isEqual(mac(userId, purpose, code), HexFormat.of().parseHex(expectedHex));
    }

    private byte[] mac(UUID userId, OtpPurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            mac.update(userId.toString().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            mac.update(purpose.name().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            mac.update(code.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (Exception ex) {
            throw new IllegalStateException("OTP hash could not be computed", ex);
        }
    }
}
