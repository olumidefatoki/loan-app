package com.mfb.lending.module.customer.application;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public final class IdentityNumbers {

    private IdentityNumbers() {
    }

    public static String bvn(String raw) {
        return digits(raw, 11, "bvn", "BVN must be 11 digits");
    }

    public static String nin(String raw) {
        return digits(raw, 11, "nin", "NIN must be 11 digits");
    }

    public static String optionalNin(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return nin(raw);
    }

    public static String accountNumber(String raw) {
        return digits(raw, 10, "accountNumber", "Account number must be 10 digits");
    }

    public static String bankCode(String raw) {
        String digits = digitsOnly(raw);
        if (digits.length() < 3 || digits.length() > 6) {
            throw ValidationException.field("bankCode", "Bank code must be 3 to 6 digits");
        }
        return digits;
    }

    public static String cac(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.field("cacRegistrationNumber", "CAC registration number is required");
        }
        String value = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!value.matches("^[A-Z]{2}\\d{5,8}$")) {
            throw ValidationException.field("cacRegistrationNumber", "CAC registration number must look like RC123456");
        }
        return value;
    }

    public static String tin(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.field("taxIdentificationNumber", "Tax identification number is required");
        }
        String value = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!value.matches("^[A-Z0-9]{8,20}$")) {
            throw ValidationException.field("taxIdentificationNumber", "Tax identification number is invalid");
        }
        return value;
    }

    public static String email(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.field(field, field + " is required");
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static String phone(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.field(field, field + " is required");
        }
        String value = raw.trim();
        if (!value.matches("^\\+[1-9]\\d{7,14}$")) {
            throw ValidationException.field(field, field + " must be in international format");
        }
        return value;
    }

    public static String masked(String normalised) {
        int keep = Math.min(4, normalised.length());
        return "*".repeat(normalised.length() - keep) + normalised.substring(normalised.length() - keep);
    }

    public static String maskedLast4(String last4) {
        return "*******" + last4.trim();
    }

    public static boolean repeatedDigit(String number, char digit) {
        return number.chars().allMatch(value -> value == digit);
    }

    private static String digits(String raw, int length, String field, String message) {
        String digits = digitsOnly(raw);
        if (digits.length() != length) {
            throw ValidationException.field(field, message);
        }
        return digits;
    }

    private static String digitsOnly(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        return digits.toString();
    }
}
