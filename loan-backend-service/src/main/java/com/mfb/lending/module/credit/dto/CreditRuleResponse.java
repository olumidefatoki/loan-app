package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CreditRuleResponse(
        UUID id,
        String code,
        String name,
        String description,
        String segment,
        UUID productId,
        String ruleType,
        boolean mandatory,
        boolean exceptionAllowed,
        BigDecimal threshold,
        String failureOutcome,
        String policyVersion,
        boolean active
) {
}
