package com.mfb.lending.module.customer.domain;

public record StoredIdentity(String ciphertext, String masked) {

    public boolean present() {
        return ciphertext != null && !ciphertext.isBlank();
    }
}
