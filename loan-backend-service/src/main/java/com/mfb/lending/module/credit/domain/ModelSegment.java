package com.mfb.lending.module.credit.domain;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public enum ModelSegment {
    INDIVIDUAL,
    SME,
    PRODUCT;

    public static ModelSegment parse(String value) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field("segment", "segment is required");
        }
        try {
            return ModelSegment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("segment", "segment must be INDIVIDUAL, SME, or PRODUCT");
        }
    }
}
