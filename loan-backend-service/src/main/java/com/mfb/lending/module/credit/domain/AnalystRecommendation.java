package com.mfb.lending.module.credit.domain;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public enum AnalystRecommendation {
    APPROVE,
    REJECT,
    REFER,
    REQUEST_INFORMATION;

    public static AnalystRecommendation parse(String value) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field("recommendation", "recommendation is required");
        }
        try {
            return AnalystRecommendation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("recommendation", "Unknown recommendation");
        }
    }
}
