package com.mfb.lending.module.auth.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mfb.lending.common.api.FieldViolation;
import com.mfb.lending.common.exception.ValidationException;

public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void check(String password, String email, String phoneNumber) {
        List<FieldViolation> violations = new ArrayList<>();
        if (password == null || password.length() < 12 || password.length() > 128) {
            violations.add(new FieldViolation("password", "Password must be between 12 and 128 characters"));
        } else {
            if (password.chars().noneMatch(Character::isUpperCase)) {
                violations.add(new FieldViolation("password", "Password must contain an uppercase letter"));
            }
            if (password.chars().noneMatch(Character::isLowerCase)) {
                violations.add(new FieldViolation("password", "Password must contain a lowercase letter"));
            }
            if (password.chars().noneMatch(Character::isDigit)) {
                violations.add(new FieldViolation("password", "Password must contain a digit"));
            }
            if (password.chars().allMatch(Character::isLetterOrDigit)) {
                violations.add(new FieldViolation("password", "Password must contain a symbol"));
            }
            String localPart = localPart(email);
            if (localPart.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(localPart)) {
                violations.add(new FieldViolation("password", "Password must not contain the email address"));
            }
            if (phoneNumber != null && phoneNumber.length() >= 6 && password.contains(phoneNumber)) {
                violations.add(new FieldViolation("password", "Password must not contain the phone number"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    private static String localPart(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "";
        }
        return email.substring(0, at).toLowerCase(Locale.ROOT);
    }
}
