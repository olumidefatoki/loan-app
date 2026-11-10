package com.mfb.lending.module.customer.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.customer.domain.SealedIdentifier;
import org.springframework.stereotype.Component;

@Component
public class IdentitySeal {

    private final AesGcmCipher cipher;
    private final byte[] key;

    public IdentitySeal(AesGcmCipher cipher, AuthProperties properties) {
        this.cipher = cipher;
        this.key = properties.encryptionKeyBytes();
    }

    public SealedIdentifier seal(String normalised) {
        return new SealedIdentifier(cipher.encrypt(normalised), hmac(normalised), IdentityNumbers.masked(normalised));
    }

    public String reveal(String ciphertext) {
        return cipher.decrypt(ciphertext);
    }

    public String hmac(String normalised) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Identity value could not be sealed", ex);
        }
    }
}
