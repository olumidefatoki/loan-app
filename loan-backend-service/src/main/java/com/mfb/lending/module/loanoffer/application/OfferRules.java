package com.mfb.lending.module.loanoffer.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ValidationException;

public final class OfferRules {

    private OfferRules() {
    }

    public static BigDecimal principal(
            BigDecimal requested,
            BigDecimal applicationAmount,
            BigDecimal approvedAmount,
            int scale,
            RoundingMode rounding) {
        if (applicationAmount == null || approvedAmount == null
                || applicationAmount.signum() <= 0 || approvedAmount.signum() <= 0) {
            throw new ConflictException("A final approval is required before an offer can be issued");
        }
        BigDecimal ceiling = applicationAmount.min(approvedAmount);
        BigDecimal selected = requested == null ? ceiling : requested;
        if (selected.signum() <= 0) {
            throw ValidationException.field("principal", "Principal must be greater than zero");
        }
        BigDecimal principal = selected.setScale(scale, rounding);
        if (principal.compareTo(ceiling) > 0) {
            throw ValidationException.field("principal", "Principal cannot exceed the approved amount of " + ceiling.toPlainString());
        }
        if (principal.signum() <= 0) {
            throw ValidationException.field("principal", "Principal must be greater than zero");
        }
        return principal;
    }
}
