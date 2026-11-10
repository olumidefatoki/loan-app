package com.mfb.lending.module.auth.application;

import java.security.SecureRandom;
import java.util.Base64;

import com.mfb.lending.module.auth.security.Base32;
import org.springframework.stereotype.Component;

@Component
public class SecureTokens {

    private final SecureRandom random = new SecureRandom();

    public String numericCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    public String refreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String totpSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return Base32.encode(bytes);
    }
}
