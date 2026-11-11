package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BureauCheckResponse(
        UUID id,
        String providerCode,
        String status,
        Boolean identificationMatched,
        Integer bureauScore,
        BigDecimal existingExposure,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
) {
}
