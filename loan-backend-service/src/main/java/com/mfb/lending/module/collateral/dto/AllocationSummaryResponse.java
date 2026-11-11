package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AllocationSummaryResponse(
        UUID id,
        UUID applicationId,
        BigDecimal allocatedAmount,
        boolean sharedSecurity,
        String status,
        Instant allocatedAt,
        Instant releasedAt
) {
}
