package com.mfb.lending.module.loanproduct.domain;

import java.util.UUID;

public record EligibilityRule(
        UUID id,
        UUID versionId,
        String ruleCode,
        String description
) {
}
