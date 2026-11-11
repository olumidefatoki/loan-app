package com.mfb.lending.module.integration.corebanking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {

    public static final String MESSAGE_ID = "X-Core-Banking-Message-Id";
    public static final String TIMESTAMP = "X-Core-Banking-Timestamp";
    public static final String SIGNATURE = "X-Core-Banking-Signature";

    private WebhookSignature() {
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception ex) {
            throw new IllegalStateException("Webhook signature could not be created", ex);
        }
    }

    public static boolean matches(String secret, String timestamp, byte[] body, String presented) {
        if (secret == null || secret.isBlank() || timestamp == null || presented == null || body == null) {
            return false;
        }
        byte[] expected;
        byte[] actual;
        try {
            expected = HexFormat.of().parseHex(sign(secret, timestamp, body));
            actual = HexFormat.of().parseHex(presented.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }
}
