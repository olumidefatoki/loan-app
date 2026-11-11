package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoringComponentSpec(
        String code,
        BigDecimal weight,
        String metric,
        ScoreDirection direction,
        BigDecimal best,
        BigDecimal worst
) {
}
