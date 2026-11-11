package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ApprovalLevelResponse(
        UUID id,
        String stage,
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
