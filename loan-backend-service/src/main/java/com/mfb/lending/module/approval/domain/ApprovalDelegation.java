package com.mfb.lending.module.approval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApprovalDelegation(
        UUID id,
        UUID userId,
        UUID approvalId,
        String roleCode,
        ApprovalStage stage,
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
