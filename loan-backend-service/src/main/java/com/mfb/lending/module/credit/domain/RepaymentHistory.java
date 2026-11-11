package com.mfb.lending.module.credit.domain;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public enum RepaymentHistory {
    CLEAN,
    MINOR_ARREARS,
    DELINQUENT,
    DEFAULT;

    public static RepaymentHistory parse(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw ValidationException.field("repaymentHistory", "repaymentHistory is required");
            }
            return null;
        }
        try {
            return RepaymentHistory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("repaymentHistory", "Unknown repayment history");
        }
    }

    public static RepaymentHistory worse(RepaymentHistory left, RepaymentHistory right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return rank(left) >= rank(right) ? left : right;
    }

    private static int rank(RepaymentHistory history) {
        return switch (history) {
            case CLEAN -> 0;
            case MINOR_ARREARS -> 1;
            case DELINQUENT -> 2;
            case DEFAULT -> 3;
        };
    }
}
