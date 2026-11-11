package com.mfb.lending.module.credit.domain;

import java.time.Instant;
import java.util.UUID;

public record CreditPolicyException(
        UUID id,
        UUID assessmentId,
        String ruleCode,
        String justification,
        String status,
        UUID requestedBy,
        Instant requestedAt,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote
) {
}
