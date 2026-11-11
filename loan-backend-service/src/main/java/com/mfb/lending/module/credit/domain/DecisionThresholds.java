package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionThresholds(BigDecimal autoApproveMinimum, BigDecimal referMinimum) {
}
