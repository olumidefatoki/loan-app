package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record CreditRule(
        UUID id,
        String code,
        String name,
        String description,
        String segment,
        UUID productId,
        RuleType ruleType,
        boolean mandatory,
        boolean exceptionAllowed,
        BigDecimal threshold,
        DecisionOutcome failureOutcome,
        String policyVersion,
        boolean active
) {
}
