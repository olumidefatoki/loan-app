package com.mfb.lending.module.customer.domain;

public record SealedIdentifier(String ciphertext, String hash, String masked) {

    public String last4() {
        return masked.substring(masked.length() - 4);
    }
}
