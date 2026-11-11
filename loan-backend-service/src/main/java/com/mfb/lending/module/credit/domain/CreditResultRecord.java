package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditResultRecord(
        UUID id,
        UUID assessmentId,
        UUID modelId,
        UUID modelVersionId,
        String policyVersion,
        BigDecimal score,
        String riskCategory,
        DecisionOutcome decision,
        String explanation,
        Instant evaluatedAt
) {
}
