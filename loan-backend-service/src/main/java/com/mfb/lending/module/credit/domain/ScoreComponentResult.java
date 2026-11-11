package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;

public record ScoreComponentResult(
        String code,
        String metric,
        BigDecimal rawValue,
        BigDecimal rawScore,
        BigDecimal weight,
        BigDecimal weightedScore,
        String explanation
) {
}
