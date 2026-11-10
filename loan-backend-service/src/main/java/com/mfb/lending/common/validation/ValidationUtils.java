package com.mfb.lending.common.validation;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mfb.lending.common.exception.ValidationException;

public final class ValidationUtils {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private ValidationUtils() {
    }

    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw ValidationException.field(field, field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    public static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null) {
            throw ValidationException.field(field, field + " is required");
        }
        if (value.signum() <= 0) {
            throw ValidationException.field(field, field + " must be greater than zero");
        }
        return value;
    }

    public static UUID requireUuid(String value, String field) {
        String text = requireText(value, field, 36);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, field + " must be a UUID");
        }
    }

    public static String requireEmail(String value, String field) {
        String email = requireText(value, field, 255);
        if (!EMAIL.matcher(email).matches()) {
            throw ValidationException.field(field, field + " must be a valid email address");
        }
        return email;
    }
}
