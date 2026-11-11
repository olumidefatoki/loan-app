package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.mfb.lending.common.exception.ValidationException;

public final class CoreMoney {

    private CoreMoney() {
    }

    public static BigDecimal ngn(BigDecimal value, String field) {
        if (value == null) {
            throw ValidationException.field(field, field + " is required");
        }
        if (value.signum() <= 0) {
            throw ValidationException.field(field, field + " must be greater than zero");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ValidationException.field(field, field + " must have at most two decimal places");
        }
    }

    public static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}
