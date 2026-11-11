package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.util.Map;

public record CalculationLine(
        String formula,
        String expression,
        Map<String, BigDecimal> inputs,
        BigDecimal output,
        String unavailableReason
) {

    public CalculationLine {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public static CalculationLine notApplicable(String formula, String expression) {
        return new CalculationLine(formula, expression, Map.of(), null, "NOT_APPLICABLE");
    }
}
