package com.mfb.lending.module.approval.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ApprovalLevel(
        UUID id,
        UUID versionId,
        ApprovalStage stage,
        int sequence,
        String roleCode,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int quorum,
        int expiryHours,
        boolean manualRequired,
        boolean automated,
        boolean escalateOnExpiry
) {
}
