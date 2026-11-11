package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveCreditRuleRequest(
        @NotBlank @Size(min = 2, max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 500) String description,
        @NotBlank String segment,
        UUID productId,
        @NotBlank String ruleType,
        boolean mandatory,
        boolean exceptionAllowed,
        BigDecimal threshold,
        @NotBlank String failureOutcome,
        @NotBlank @Size(max = 40) String policyVersion,
        Boolean active
) {
}
