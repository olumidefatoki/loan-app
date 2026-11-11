package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DelegationResponse(
        UUID id,
        UUID userId,
        UUID approvalId,
        String roleCode,
        String stage,
        UUID branchId,
        BigDecimal maxAmount,
        String currencyCode,
        Instant validFrom,
        Instant validUntil,
        boolean active,
        String reason,
        UUID grantedBy,
        Instant createdAt
) {
}
