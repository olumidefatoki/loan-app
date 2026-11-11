package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditBureauCheck(
        UUID id,
        UUID assessmentId,
        UUID customerId,
        String providerCode,
        BureauCheckStatus status,
        Boolean identificationMatched,
        Integer bureauScore,
        BigDecimal existingExposure,
        String mappedResponseJson,
        String failureReason,
        UUID requestedBy,
        Instant requestedAt,
        Instant completedAt
) {
}
