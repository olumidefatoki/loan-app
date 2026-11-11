package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AllocationRecord(
        UUID id,
        UUID applicationId,
        UUID collateralId,
        BigDecimal allocatedAmount,
        boolean sharedSecurity,
        PledgeStatus status,
        Instant allocatedAt,
        Instant releasedAt,
        UUID allocatedBy
) {
}
