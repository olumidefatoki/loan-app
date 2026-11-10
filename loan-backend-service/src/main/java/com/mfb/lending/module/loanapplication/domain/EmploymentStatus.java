package com.mfb.lending.module.loanapplication.domain;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public enum EmploymentStatus {
    EMPLOYED,
    SELF_EMPLOYED,
    UNEMPLOYED,
    RETIRED,
    STUDENT;

    public boolean requiresEmployer() {
        return this == EMPLOYED || this == SELF_EMPLOYED;
    }

    public static EmploymentStatus parse(String value, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw ValidationException.field(field, field + " is required");
            }
            return null;
        }
        try {
            return EmploymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, "Unknown employment status");
        }
    }
}
