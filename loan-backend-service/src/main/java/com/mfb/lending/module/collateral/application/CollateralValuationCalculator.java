package com.mfb.lending.module.collateral.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.collateral.domain.CollateralType;

public final class CollateralValuationCalculator {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int MONEY_SCALE = 2;
    private static final int RATIO_SCALE = 6;

    private CollateralValuationCalculator() {
    }

    public static EligibleValue value(
            CollateralType type,
            BigDecimal marketValue,
            BigDecimal forcedSaleValue,
            BigDecimal statedEligibleValue,
            BigDecimal lendingRatio) {
        BigDecimal market = money(marketValue, "marketValue");
        BigDecimal forcedSale = money(forcedSaleValue, "forcedSaleValue");
        if (market.signum() <= 0) {
            throw ValidationException.field("marketValue", "Market value must be greater than zero");
        }
        if (forcedSale.signum() <= 0) {
            throw ValidationException.field("forcedSaleValue", "Forced sale value must be greater than zero");
        }
        if (forcedSale.compareTo(market) > 0) {
            throw ValidationException.field("forcedSaleValue", "Forced sale value cannot exceed market value");
        }
        BigDecimal ratio = ratio(type, lendingRatio);
        BigDecimal calculated = market.multiply(ratio).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (calculated.compareTo(forcedSale) > 0) {
            calculated = forcedSale;
        }
        if (calculated.signum() <= 0) {
            throw ValidationException.field("marketValue", "Eligible lending value must be greater than zero");
        }
        BigDecimal accepted = calculated;
        if (statedEligibleValue != null) {
            accepted = money(statedEligibleValue, "eligibleLendingValue");
            if (accepted.signum() <= 0) {
                throw ValidationException.field("eligibleLendingValue", "Eligible lending value must be greater than zero");
            }
            if (accepted.compareTo(calculated) > 0) {
                throw ValidationException.field(
                        "eligibleLendingValue",
                        "Eligible lending value cannot exceed the calculated collateral value");
            }
        }
        return new EligibleValue(market, forcedSale, ratio, calculated, accepted);
    }

    public static BigDecimal coverage(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal available(BigDecimal eligible, BigDecimal allocated) {
        BigDecimal safeEligible = eligible == null ? new BigDecimal("0.00") : eligible.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal safeAllocated = allocated == null ? new BigDecimal("0.00") : allocated.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal remaining = safeEligible.subtract(safeAllocated).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return remaining.signum() < 0 ? new BigDecimal("0.00") : remaining;
    }

    public static BigDecimal ratio(CollateralType type, BigDecimal override) {
        BigDecimal policy = type.policyRatio().setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        if (override == null) {
            return policy;
        }
        BigDecimal requested = override.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        if (requested.signum() <= 0 || requested.compareTo(ONE) > 0) {
            throw ValidationException.field("lendingRatio", "Lending ratio must be greater than 0 and at most 1");
        }
        if (requested.compareTo(policy) > 0) {
            throw ValidationException.field(
                    "lendingRatio",
                    "Lending ratio cannot exceed the policy ratio for this collateral type");
        }
        return requested;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw ValidationException.field(field, field + " is required");
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public record EligibleValue(
            BigDecimal marketValue,
            BigDecimal forcedSaleValue,
            BigDecimal lendingRatio,
            BigDecimal calculatedEligibleValue,
            BigDecimal acceptedEligibleValue
    ) {
    }
}
