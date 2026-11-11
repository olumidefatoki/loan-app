package com.mfb.lending.module.credit.domain;

public record CalculationSet(
        CalculationLine dti,
        CalculationLine dscr,
        CalculationLine collateralCoverage,
        CalculationLine disposableIncome
) {
}
