package com.mfb.lending.module.auth.application;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public final class Identifiers {

    private Identifiers() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.field("identifier", "identifier is required");
        }
        String value = raw.trim();
        if (value.length() > 255) {
            throw ValidationException.field("identifier", "identifier must be at most 255 characters");
        }
        if (value.indexOf('@') >= 0) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value;
    }

    public static boolean isEmail(String identifier) {
        return identifier.indexOf('@') >= 0;
    }
}
