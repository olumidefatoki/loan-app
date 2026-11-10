package com.mfb.lending.module.auth.security;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TotpVerifier {

    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;

    public OptionalLong matchStep(String base32Secret, String code, Instant now, Long lastAcceptedStep) {
        if (code == null || !code.chars().allMatch(Character::isDigit) || code.length() != DIGITS) {
            return OptionalLong.empty();
        }
        byte[] secret;
        try {
            secret = Base32.decode(base32Secret);
        } catch (IllegalArgumentException ex) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long candidate = current - 1; candidate <= current + 1; candidate++) {
            if (candidate < 0 || (lastAcceptedStep != null && candidate <= lastAcceptedStep)) {
                continue;
            }
            if (MessageDigest.isEqual(
                    codeAt(secret, candidate).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return OptionalLong.of(candidate);
            }
        }
        return OptionalLong.empty();
    }

    public String codeAt(String base32Secret, Instant time) {
        return codeAt(Base32.decode(base32Secret), step(time));
    }

    public long step(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
    }

    private static String codeAt(byte[] secret, long counter) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return "%06d".formatted(otp);
        } catch (Exception ex) {
            throw new IllegalStateException("TOTP could not be computed", ex);
        }
    }
}
