package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditResultSummary(
        UUID id,
        UUID modelId,
        UUID modelVersionId,
        String policyVersion,
        BigDecimal score,
        String riskCategory,
        String decision,
        String explanation,
        Instant evaluatedAt
) {
}
